package com.air.advantage.aaservice.data.mailbox

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OkHttpMailboxWsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpMailboxWsClient
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val openCount = AtomicInteger(0)
    private val clientOutbound = CopyOnWriteArrayList<JSONObject>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        openCount.set(0)
        clientOutbound.clear()
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) {
            runCatching { client.disconnect() }
        }
        runCatching { server.shutdown() }
        scope.cancel()
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun `connect then mailbox_snapshot reaches Connected`() = runBlocking {
        installUpgradeDispatcher { webSocket, _ ->
            webSocket.send(MailboxFixtures.snapshot())
        }

        createClient()
        client.connect()

        val state = awaitState { it is MailboxConnectionState.Connected }
        assertEquals(MailboxConnectionState.Connected, state)
        assertEquals(1, openCount.get())
    }

    @Test
    fun `sendUpdate awaits matching ack success by msg_id`() = runBlocking {
        installAckingDispatcher(success = true)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = client.sendUpdate(
            register = "system_status",
            payload = JSONObject().put("airconOn", false),
        )

        assertEquals(MailboxAckStatus.SUCCESS, ack.status)
        assertFalse(ack.msgId.isNullOrBlank())

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.MAILBOX_UPDATE }
        assertEquals(ack.msgId, outbound.getString("msg_id"))
        assertEquals("system_status", outbound.getString("register"))
    }

    @Test
    fun `sendUpdate surfaces matching ack error by msg_id`() = runBlocking {
        installAckingDispatcher(success = false)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = client.sendUpdate(
            register = "zone_config",
            payload = JSONObject().put("zones", 99),
        )

        assertEquals(MailboxAckStatus.ERROR, ack.status)
        assertEquals("register write rejected", ack.reason)
        assertFalse(ack.msgId.isNullOrBlank())

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.MAILBOX_UPDATE }
        assertEquals(ack.msgId, outbound.getString("msg_id"))
    }

    @Test
    fun `sendResync awaits matching ack success by msg_id`() = runBlocking {
        installAckingDispatcher(success = true)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = client.sendResync()

        assertEquals(MailboxAckStatus.SUCCESS, ack.status)
        assertFalse(ack.msgId.isNullOrBlank())

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.COMMAND }
        assertEquals(MailboxCommandAction.RESYNC_MAILBOX, outbound.getString("action"))
        assertEquals(ack.msgId, outbound.getString("msg_id"))
    }

    @Test
    fun `sendResync surfaces matching ack error by msg_id`() = runBlocking {
        installAckingDispatcher(success = false)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = client.sendResync()

        assertEquals(MailboxAckStatus.ERROR, ack.status)
        assertEquals("register write rejected", ack.reason)

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.COMMAND }
        assertEquals(ack.msgId, outbound.getString("msg_id"))
    }

    @Test
    fun `close 4009 becomes Rejected and does not reconnect`() = runBlocking {
        val serverSocket = AtomicReference<WebSocket>()
        installUpgradeDispatcher { webSocket, _ ->
            serverSocket.set(webSocket)
            webSocket.send(MailboxFixtures.snapshot())
        }

        createClient(
            reconnectInitialDelayMs = 40L,
            reconnectMaxDelayMs = 80L,
        )
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        checkNotNull(serverSocket.get()).close(
            OkHttpMailboxWsClient.CLOSE_SINGLE_CLIENT,
            "Single client limit enforced",
        )

        val rejected = awaitState { it is MailboxConnectionState.Rejected }
            as MailboxConnectionState.Rejected
        assertEquals(OkHttpMailboxWsClient.CLOSE_SINGLE_CLIENT, rejected.code)
        assertEquals("Single client limit enforced", rejected.reason)

        val opensAfterReject = openCount.get()
        Thread.sleep(250L)
        assertEquals(
            "must not open a second connection after 4009",
            opensAfterReject,
            openCount.get(),
        )
        assertTrue(client.connectionState.value is MailboxConnectionState.Rejected)
    }

    @Test
    fun `explicit disconnect reaches Idle and does not reconnect`() = runBlocking {
        val serverSocket = AtomicReference<WebSocket>()
        installUpgradeDispatcher { webSocket, _ ->
            serverSocket.set(webSocket)
            webSocket.send(MailboxFixtures.snapshot())
        }

        createClient(
            reconnectInitialDelayMs = 40L,
            reconnectMaxDelayMs = 80L,
        )
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val opensAfterConnect = openCount.get()
        client.disconnect()

        assertEquals(MailboxConnectionState.Idle, awaitState { it is MailboxConnectionState.Idle })
        // Server-side close after client disconnect must not schedule reconnect.
        runCatching { checkNotNull(serverSocket.get()).close(1000, "after client disconnect") }

        Thread.sleep(250L)
        assertEquals(
            "must not open another connection after explicit disconnect",
            opensAfterConnect,
            openCount.get(),
        )
        assertEquals(MailboxConnectionState.Idle, client.connectionState.value)
    }

    @Test
    fun `unexpected drop schedules at least one reconnect attempt`() = runBlocking {
        val serverSocket = AtomicReference<WebSocket>()
        val secondOpen = CountDownLatch(1)
        installUpgradeDispatcher { webSocket, _ ->
            val n = openCount.get()
            if (n == 1) {
                serverSocket.set(webSocket)
                webSocket.send(MailboxFixtures.snapshot())
            } else if (n >= 2) {
                webSocket.send(MailboxFixtures.snapshot())
                secondOpen.countDown()
            }
        }

        createClient(
            reconnectInitialDelayMs = 40L,
            reconnectMaxDelayMs = 80L,
        )
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        // Abrupt server close after Connected (avoid WebSocket.cancel — MockWebServer NPE).
        checkNotNull(serverSocket.get()).close(1001, "test drop")

        assertTrue(
            "expected reconnect open within timeout",
            secondOpen.await(3, TimeUnit.SECONDS),
        )
        assertTrue(openCount.get() >= 2)
    }

    @Test
    fun `unknown type ignored and optional error appears on incoming without killing connection`() =
        runBlocking {
            val serverSocket = AtomicReference<WebSocket>()
            val sawSnapshot = CountDownLatch(1)
            val sawError = CountDownLatch(1)
            val sawUnknown = AtomicInteger(0)

            installUpgradeDispatcher { webSocket, _ ->
                serverSocket.set(webSocket)
                webSocket.send(MailboxFixtures.snapshot())
            }

            createClient()
            val collectJob = scope.launch {
                client.incoming.collect { inbound ->
                    when (inbound) {
                        is MailboxInbound.Snapshot -> sawSnapshot.countDown()
                        is MailboxInbound.Error -> sawError.countDown()
                        is MailboxInbound.Unknown -> sawUnknown.incrementAndGet()
                        else -> Unit
                    }
                }
            }

            client.connect()
            awaitState { it is MailboxConnectionState.Connected }
            assertTrue("expected snapshot on incoming", sawSnapshot.await(3, TimeUnit.SECONDS))

            val ws = checkNotNull(serverSocket.get())
            ws.send(MailboxFixtures.unknownType())
            ws.send(MailboxFixtures.protocolError())

            assertTrue("expected protocol error on incoming", sawError.await(3, TimeUnit.SECONDS))
            assertEquals(0, sawUnknown.get())
            assertEquals(MailboxConnectionState.Connected, client.connectionState.value)

            Thread.sleep(100L)
            assertEquals(0, sawUnknown.get())
            collectJob.cancel()
        }

    @Test
    fun `FakeMailboxWsClient documents MailboxWsClient surface`() = runBlocking {
        val fake = FakeMailboxWsClient()
        val asInterface: MailboxWsClient = fake
        asInterface.connect()
        assertEquals(MailboxConnectionState.Connecting, asInterface.connectionState.value)

        val ack = asInterface.sendUpdate("system_status", JSONObject().put("airconOn", true))
        assertEquals(MailboxAckStatus.SUCCESS, ack.status)
        assertEquals(1, fake.sentUpdates.size)

        asInterface.disconnect()
        assertEquals(MailboxConnectionState.Idle, asInterface.connectionState.value)
        assertEquals(1, fake.connectCalls)
        assertEquals(1, fake.disconnectCalls)
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun createClient(
        reconnectInitialDelayMs: Long = 50L,
        reconnectMaxDelayMs: Long = 100L,
        ackTimeoutMs: Long = 2_000L,
    ) {
        val wsUrl = server.url("/v1/mailbox-stream").toString()
            .replaceFirst("http", "ws")
        val config = MailboxWsConfig(
            url = wsUrl,
            pingIntervalMs = 0L,
            reconnectInitialDelayMs = reconnectInitialDelayMs,
            reconnectMaxDelayMs = reconnectMaxDelayMs,
            ackTimeoutMs = ackTimeoutMs,
        )
        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        client = OkHttpMailboxWsClient(config = config, client = http, scope = scope)
    }

    private fun installUpgradeDispatcher(onOpen: (WebSocket, Response) -> Unit) {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openCount.incrementAndGet()
                onOpen(webSocket, response)
            }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(listener)
        }
    }

    private fun installAckingDispatcher(success: Boolean) {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openCount.incrementAndGet()
                webSocket.send(MailboxFixtures.snapshot())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                clientOutbound.add(json)
                val msgId = json.optString("msg_id", "")
                if (msgId.isEmpty()) return
                val reply = if (success) {
                    MailboxFixtures.ackSuccess(msgId)
                } else {
                    MailboxFixtures.ackError(msgId)
                }
                webSocket.send(reply)
            }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(listener)
        }
    }

    private suspend fun awaitState(
        timeoutMs: Long = 3_000L,
        predicate: (MailboxConnectionState) -> Boolean,
    ): MailboxConnectionState = withTimeout(timeoutMs) {
        client.connectionState.first(predicate)
    }

    private fun awaitOutbound(
        timeoutMs: Long = 3_000L,
        predicate: (JSONObject) -> Boolean,
    ): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            clientOutbound.firstOrNull(predicate)?.let { return it }
            Thread.sleep(10L)
        }
        throw AssertionError(
            "timed out waiting for outbound frame; seen=${clientOutbound.map { it.toString() }}",
        )
    }
}
