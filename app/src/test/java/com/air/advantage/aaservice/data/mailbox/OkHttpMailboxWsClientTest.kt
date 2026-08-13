package com.air.advantage.aaservice.data.mailbox

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
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
    fun `sendWrite awaits matching ack success by msg_id`() = runBlocking {
        installAckingDispatcher(success = true)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = client.sendWrite(
            register = "system_status",
            payload = MailboxPayload.Typed(JSONObject().put("airconOn", false)),
        )

        assertEquals(MailboxAckStatus.SUCCESS, ack.status)
        assertFalse(ack.msgId.isNullOrBlank())

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.WRITE }
        assertEquals(ack.msgId, outbound.getString("msg_id"))
        assertEquals("system_status", outbound.getString("register"))
    }

    @Test
    fun `sendWrite surfaces matching ack error by msg_id`() = runBlocking {
        installAckingDispatcher(success = false)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = client.sendWrite(
            register = "zone_config",
            payload = MailboxPayload.Typed(JSONObject().put("zones", 99)),
        )

        assertEquals(MailboxAckStatus.ERROR, ack.status)
        assertEquals("register write rejected", ack.reason)
        assertFalse(ack.msgId.isNullOrBlank())

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.WRITE }
        assertEquals(ack.msgId, outbound.getString("msg_id"))
    }

    @Test
    fun `sendCommand resync awaits matching ack success by msg_id`() = runBlocking {
        installAckingDispatcher(success = true)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = client.sendCommand(MailboxCommandAction.RESYNC)

        assertEquals(MailboxAckStatus.SUCCESS, ack.status)
        assertFalse(ack.msgId.isNullOrBlank())

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.COMMAND }
        assertEquals(MailboxCommandAction.RESYNC, outbound.getString("action"))
        assertEquals(ack.msgId, outbound.getString("msg_id"))
    }

    @Test
    fun `sendCommand resync surfaces matching ack error by msg_id`() = runBlocking {
        installAckingDispatcher(success = false)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = client.sendCommand(MailboxCommandAction.RESYNC)

        assertEquals(MailboxAckStatus.ERROR, ack.status)
        assertEquals("register write rejected", ack.reason)

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.COMMAND }
        assertEquals(ack.msgId, outbound.getString("msg_id"))
    }

    @Test
    fun `close 4009 falls back to generic Disconnected and reconnects`() = runBlocking {
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

        checkNotNull(serverSocket.get()).close(4009, "Single client limit enforced")

        assertEquals(
            MailboxConnectionState.Disconnected,
            awaitState { it is MailboxConnectionState.Disconnected },
        )
        assertTrue(
            "expected reconnect open within timeout",
            secondOpen.await(3, TimeUnit.SECONDS),
        )
        assertTrue(openCount.get() >= 2)
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

        val ack = asInterface.sendWrite("system_status", MailboxPayload.Typed(JSONObject().put("airconOn", true)))
        assertEquals(MailboxAckStatus.SUCCESS, ack.status)
        assertEquals(1, fake.sentWrites.size)

        asInterface.disconnect()
        assertEquals(MailboxConnectionState.Idle, asInterface.connectionState.value)
        assertEquals(1, fake.connectCalls)
        assertEquals(1, fake.disconnectCalls)
    }

    @Test
    fun `sendRead awaits matching read_result by msg_id`() = runBlocking {
        installAckingDispatcher(success = true)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val outcome = client.sendRead(register = "zone_state")

        val value = outcome as? ReadOutcome.Value
        checkNotNull(value)
        assertEquals("zone_state", value.result.register)
        assertFalse(value.result.msgId.isNullOrBlank())

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.READ }
        assertEquals(MailboxMessageType.READ, outbound.getString("type"))
        assertEquals("zone_state", outbound.getString("register"))
        assertEquals(value.result.msgId, outbound.getString("msg_id"))
    }

    @Test
    fun `sendRead surfaces error ack by msg_id`() = runBlocking {
        installAckingDispatcher(success = false)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val outcome = client.sendRead(register = "zone_state")

        val error = outcome as? ReadOutcome.Error
        checkNotNull(error)
        assertEquals(MailboxAckStatus.ERROR, error.ack.status)
        assertEquals("register 03 has no value", error.ack.reason)

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.READ }
        assertEquals(MailboxMessageType.READ, outbound.getString("type"))
    }

    @Test
    fun `sendRead throws MailboxAckTimeoutException on timeout`() {
        runBlocking {
            installNoReplyDispatcher()
            createClient(ackTimeoutMs = 500L)
            client.connect()
            awaitState { it is MailboxConnectionState.Connected }

            val thrown = try {
                client.sendRead(register = "zone_state")
                null
            } catch (e: MailboxAckTimeoutException) {
                e
            }
            checkNotNull(thrown)
        }
    }

    @Test
    fun `sendWrite throws MailboxAckTimeoutException on timeout`() {
        runBlocking {
            installNoReplyDispatcher()
            createClient(ackTimeoutMs = 500L)
            client.connect()
            awaitState { it is MailboxConnectionState.Connected }

            val thrown = try {
                client.sendWrite(
                    register = "system_status",
                    payload = MailboxPayload.Typed(JSONObject().put("airconOn", false)),
                )
                null
            } catch (e: MailboxAckTimeoutException) {
                e
            }
            checkNotNull(thrown)
        }
    }

    @Test
    fun `sendCommand throws MailboxAckTimeoutException on timeout`() {
        runBlocking {
            installNoReplyDispatcher()
            createClient(ackTimeoutMs = 500L)
            client.connect()
            awaitState { it is MailboxConnectionState.Connected }

            val thrown = try {
                client.sendCommand(MailboxCommandAction.RESYNC)
                null
            } catch (e: MailboxAckTimeoutException) {
                e
            }
            checkNotNull(thrown)
        }
    }

    @Test
    fun `sendRead pending read resolves to ReadOutcome Error when socket drops`() = runBlocking {
        val serverSocket = AtomicReference<WebSocket>()
        installNoReplyDispatcher(serverSocket)
        createClient(
            reconnectInitialDelayMs = 40L,
            reconnectMaxDelayMs = 80L,
            ackTimeoutMs = 5_000L,
        )
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val outcome = CompletableDeferred<ReadOutcome>()
        val readJob = scope.launch {
            outcome.complete(client.sendRead(register = "zone_state"))
        }
        awaitOutbound { it.optString("type") == MailboxMessageType.READ }

        // No reply ever sent for the read — force the socket to end instead.
        checkNotNull(serverSocket.get()).close(1001, "test drop")

        val result = withTimeout(3_000L) { outcome.await() }
        val error = result as? ReadOutcome.Error
        checkNotNull(error) { "expected ReadOutcome.Error, got $result" }
        assertFalse(
            "reason must be non-null on drop-failed read",
            error.ack.reason.isNullOrBlank(),
        )
        readJob.join()
    }

    @Test
    fun `sendWrite pending ack resolves to ERROR when socket drops`() = runBlocking {
        val serverSocket = AtomicReference<WebSocket>()
        installNoReplyDispatcher(serverSocket)
        createClient(
            reconnectInitialDelayMs = 40L,
            reconnectMaxDelayMs = 80L,
            ackTimeoutMs = 5_000L,
        )
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = CompletableDeferred<MailboxInbound.Ack>()
        val writeJob = scope.launch {
            ack.complete(
                client.sendWrite(
                    register = "system_status",
                    payload = MailboxPayload.Typed(JSONObject().put("airconOn", true)),
                ),
            )
        }
        awaitOutbound { it.optString("type") == MailboxMessageType.WRITE }

        checkNotNull(serverSocket.get()).close(1001, "test drop")

        val result = withTimeout(3_000L) { ack.await() }
        assertEquals(MailboxAckStatus.ERROR, result.status)
        assertFalse("reason must be non-null on drop-failed write", result.reason.isNullOrBlank())
        writeJob.join()
    }

    @Test
    fun `sendWrite with RawHex payload emits string payload`() = runBlocking {
        installAckingDispatcher(success = true)
        createClient(ackTimeoutMs = 2_000L)
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        val ack = client.sendWrite(
            register = "0c",
            payload = MailboxPayload.RawHex("0701000000000000000000000"),
        )

        assertEquals(MailboxAckStatus.SUCCESS, ack.status)

        val outbound = awaitOutbound { it.optString("type") == MailboxMessageType.WRITE }
        assertTrue(outbound.get("payload") is String)
        assertEquals("0701000000000000000000000", outbound.getString("payload"))
    }

    @Test
    fun `daemonStatus emits status frames without failing the socket`() = runBlocking {
        val serverSocket = AtomicReference<WebSocket>()
        installUpgradeDispatcher { webSocket, _ ->
            serverSocket.set(webSocket)
            webSocket.send(MailboxFixtures.snapshot())
        }

        createClient()
        client.connect()
        awaitState { it is MailboxConnectionState.Connected }

        checkNotNull(serverSocket.get()).send(
            JSONObject()
                .put("type", MailboxMessageType.STATUS)
                .put("state", "link_down")
                .put("detail", "TCP keepalive timed out")
                .toString(),
        )

        val status = withTimeout(3_000L) { client.daemonStatus.first() }
        assertEquals("link_down", status.state)
        assertEquals("TCP keepalive timed out", status.detail)
        assertEquals(MailboxConnectionState.Connected, client.connectionState.value)
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
                val type = json.optString("type", "")
                val reply = when {
                    type == MailboxMessageType.READ && success ->
                        MailboxFixtures.readResult(msgId, json.optString("register"))
                    type == MailboxMessageType.READ ->
                        JSONObject()
                            .put("type", MailboxMessageType.ACK)
                            .put("msg_id", msgId)
                            .put("status", "error")
                            .put("reason", "register 03 has no value")
                            .toString()
                    success -> MailboxFixtures.ackSuccess(msgId)
                    else -> MailboxFixtures.ackError(msgId)
                }
                webSocket.send(reply)
            }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(listener)
        }
    }

    private fun installNoReplyDispatcher(socketRef: AtomicReference<WebSocket>? = null) {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openCount.incrementAndGet()
                socketRef?.set(webSocket)
                webSocket.send(MailboxFixtures.snapshot())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                clientOutbound.add(JSONObject(text))
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
