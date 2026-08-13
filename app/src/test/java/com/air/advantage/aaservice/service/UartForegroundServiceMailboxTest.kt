package com.air.advantage.aaservice.service

import android.content.Intent
import com.air.advantage.aaservice.data.mailbox.FakeMailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxAckStatus
import com.air.advantage.aaservice.data.mailbox.MailboxConnectionState
import com.air.advantage.aaservice.data.mailbox.MailboxFixtures
import com.air.advantage.aaservice.data.mailbox.MailboxInbound
import com.air.advantage.aaservice.data.mailbox.MailboxMessageType
import com.air.advantage.aaservice.data.mailbox.MailboxPayload
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.data.mailbox.OutboundMailboxAction
import com.air.advantage.aaservice.receiver.AlertDialogReceiver
import com.air.advantage.aaservice.util.PreferencesManager
import com.air.advantage.aaservice.util.TransportMode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * B-6 (#78) service wiring: [UartForegroundService.attachMailboxWsClient] /
 * [UartForegroundService.onMailboxInbound] dispatch every broker inbound type —
 * `snapshot` → `MESSAGE_FROM_CB_SECURE` full rawCan re-encode, `event` → single-record
 * rawCan delta, `read_result` → reconciliation
 * rawCan, `status` → [TransportStatusStore] + notification via the daemonStatus
 * collector, `error` → transient alert — plus the `deviceOpen || mailbox Connected`
 * publish gate (design `41-mailbox-to-message-from-cb.md` §6, `078-…dispatch.md`).
 *
 * Uses [FakeMailboxWsClient] (in-memory, no real socket) the same way
 * [com.air.advantage.aaservice.data.mailbox.OkHttpMailboxWsClientTest] documents its surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UartForegroundServiceMailboxTest {
    private lateinit var service: UartForegroundService
    private lateinit var fakeClient: FakeMailboxWsClient
    private val capturedIntents = mutableListOf<Intent>()

    @Before
    fun setUp() {
        val controller = Robolectric.buildService(UartForegroundService::class.java)
        service = spy(controller.get())
        capturedIntents.clear()
        AlertDialogReceiver.alertActive.set(false)
        TransportStatusStore.reset()

        doReturn("com.air.advantage.aaservice2").whenever(service).packageName
        whenever(service.registerReceiver(any(), any())).thenReturn(null)
        whenever(service.registerReceiver(any(), any(), anyOrNull(), anyOrNull())).thenReturn(null)
        doNothing().whenever(service).unregisterReceiver(any())
        doAnswer { invocation ->
            capturedIntents.add(invocation.getArgument(0))
            null
        }.whenever(service).sendBroadcast(any<Intent>())
        doAnswer { invocation ->
            capturedIntents.add(invocation.getArgument(0))
            null
        }.whenever(service).sendBroadcast(any<Intent>(), anyOrNull())

        UartForegroundService.instance = service
        fakeClient = FakeMailboxWsClient()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
        AlertDialogReceiver.alertActive.set(false)
        TransportStatusStore.reset()
    }

    private fun sentMessageFromCb(): List<Intent> =
        capturedIntents.filter { it.action == "com.air.advantage.MESSAGE_FROM_CB" }

    private fun secureRawCanFrames(): List<Intent> =
        capturedIntents.filter { it.action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" }

    private fun snapshotInbound(): MailboxInbound.Snapshot =
        MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot

    private fun brokerSnapshotInbound(): MailboxInbound.Snapshot =
        MailboxInbound.parse(MailboxFixtures.load("mailbox/mailbox_snapshot_broker.json"))
            as MailboxInbound.Snapshot

    private fun zoneEventInbound(): MailboxInbound.Event =
        MailboxInbound.parse(MailboxFixtures.event()) as MailboxInbound.Event

    private fun statusInbound(state: String): MailboxInbound.Status =
        MailboxInbound.parse(JSONObject().put("type", MailboxMessageType.STATUS).put("state", state))
            as MailboxInbound.Status

    /**
     * Full collector wiring: prefs(Ws) + factory + router client + the connectionState
     * and daemonStatus collectors via [UartForegroundService.syncMailboxInboundCollector].
     */
    private fun attachWithRouterSync() {
        val prefs = PreferencesManager(service)
        prefs.transportMode = TransportMode.Ws
        fakeClient = FakeMailboxWsClient()
        service.preferencesManager = prefs
        service.mailboxWsClientFactory = MailboxWsClientFactory { fakeClient }
        service.ensureTransportRouter().applyMode(TransportMode.Ws)
        service.syncMailboxInboundCollector()
    }

    /** Collectors + outbound sends run on Dispatchers.IO; give them a moment. */
    private fun awaitIo() {
        TimeUnit.MILLISECONDS.sleep(300)
    }

    private fun expectedReconcileReads(): List<Pair<String, Int?>> {
        val reads = mutableListOf<Pair<String, Int?>>("01" to null, "05" to null, "08" to null)
        for (zone in 1..10) reads += "03" to zone
        return reads
    }

    // ── snapshot → MESSAGE_FROM_CB_SECURE full rawCan re-encode (D1) ──

    @Test
    fun `snapshot broadcasts only the MESSAGE_FROM_CB_SECURE rawCan re-encode`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(brokerSnapshotInbound())

        assertEquals(
            "WS-path XML poll broadcasts must not exist, got ${sentMessageFromCb().size}",
            0,
            sentMessageFromCb().size,
        )

        val secure = secureRawCanFrames()
        assertEquals(
            "snapshot must broadcast exactly one secure rawCan frame, got ${secure.size}",
            1,
            secure.size,
        )
        assertEquals(
            "rawCan",
            secure.first().getStringExtra("com.air.advantage.GET_DATA_REQUEST"),
        )
        val frame = secure.first().getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE")
        assertNotNull(frame)
        assertTrue(frame!!.startsWith("getCAN 1 "))
    }

    @Test
    fun `snapshot emits MESSAGE_FROM_CB_SECURE rawCan re-encoded from typed registers`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(brokerSnapshotInbound())

        val secure = secureRawCanFrames()
        assertTrue("expected rawCan secure broadcast", secure.isNotEmpty())
        assertEquals(
            "rawCan",
            secure.first().getStringExtra("com.air.advantage.GET_DATA_REQUEST"),
        )
        val frame = secure.first().getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE")
        assertNotNull(frame)
        assertTrue(frame!!.startsWith("getCAN 1 "))
    }

    @Test
    fun `handleGetCan content-dedups identical frames and forwards different frames`() {
        service.handleGetCan("getCAN 1 0703181f30a00000000000000")
        service.handleGetCan("getCAN 1 0703181f30a00000000000000")

        assertEquals(
            "content-identical frames must broadcast once, got ${secureRawCanFrames().size}",
            1,
            secureRawCanFrames().size,
        )

        service.handleGetCan("getCAN 1 0703181f30a00000000000001")

        assertEquals(
            "different frame must rebroadcast, got ${secureRawCanFrames().size}",
            2,
            secureRawCanFrames().size,
        )
    }

    // ── mailbox_event → single-record rawCan delta ────

    @Test
    fun `mailbox_event updates propagate without restart`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(snapshotInbound())
        fakeClient.emitIncoming(zoneEventInbound())

        val secure = secureRawCanFrames()
        assertTrue("expected event rawCan delta broadcast", secure.isNotEmpty())
        val frame = secure.last().getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE")!!
        assertTrue("delta must be a getCAN 1 frame", frame.startsWith("getCAN 1 "))
        val record = frame.substringAfter("getCAN 1 ")
        assertEquals("delta must be a single 25-char record, got: $frame", 25, record.length)

        // No onCreate/onDestroy round trip happened between snapshot and event.
        assertEquals(service, UartForegroundService.instance)
    }

    @Test
    fun `event emits single-record secure rawCan delta`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(zoneEventInbound())

        val secure = secureRawCanFrames()
        assertTrue("expected event rawCan delta broadcast", secure.isNotEmpty())
        val frame = secure.first().getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE")!!
        assertTrue("delta must be a getCAN 1 frame", frame.startsWith("getCAN 1 "))
        val record = frame.substringAfter("getCAN 1 ")
        assertEquals("delta must be a single 25-char record, got: $frame", 25, record.length)
    }

    // ── read_result → reconciliation rawCan re-encode ────────────

    @Test
    fun `read_result re-encodes to secure rawCan`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(
            MailboxInbound.parse(MailboxFixtures.readResult("r1", "05")),
        )

        val secure = secureRawCanFrames()
        assertTrue("expected read_result rawCan broadcast", secure.isNotEmpty())
        val frame = secure.first().getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE")!!
        assertTrue(frame.startsWith("getCAN 1 "))
        assertEquals(25, frame.substringAfter("getCAN 1 ").length)
    }

    // ── broker status → ModeSwitchStatus + notification (D2) ─────

    @Test
    fun `status synced publishes Connected and shows notification`() {
        attachWithRouterSync()
        awaitIo()
        // connectionState stays Connecting: Connected + showNotification(true) must come
        // from the daemonStatus collector alone — the socket-state collector can never
        // produce either while Connecting.
        assertEquals(ModeSwitchStatus.Connecting, TransportStatusStore.status.value)

        fakeClient.emitDaemonStatus(statusInbound("synced"))
        awaitIo()

        assertEquals(ModeSwitchStatus.Connected, TransportStatusStore.status.value)
        verify(service, atLeastOnce()).showNotification(true)
    }

    @Test
    fun `status link_down publishes Error and hides notification when USB closed`() {
        attachWithRouterSync()
        awaitIo()
        // Symmetric to the synced test: Error + showNotification(false) are only
        // reachable through the daemonStatus collector here.
        assertEquals(ModeSwitchStatus.Connecting, TransportStatusStore.status.value)

        fakeClient.emitDaemonStatus(statusInbound("link_down"))
        awaitIo()

        assertEquals(ModeSwitchStatus.Error, TransportStatusStore.status.value)
        verify(service, atLeastOnce()).showNotification(false)
    }

    @Test
    fun `status resyncing and negotiating publish Connecting without notification`() {
        attachWithRouterSync()
        awaitIo()

        fakeClient.emitDaemonStatus(statusInbound("resyncing"))
        awaitIo()
        assertEquals(ModeSwitchStatus.Connecting, TransportStatusStore.status.value)

        fakeClient.emitDaemonStatus(statusInbound("negotiating"))
        awaitIo()
        assertEquals(ModeSwitchStatus.Connecting, TransportStatusStore.status.value)

        verify(service, never()).showNotification(true)
    }

    @Test
    fun `status unknown state leaves store unchanged and does not notify`() {
        attachWithRouterSync()
        awaitIo()

        fakeClient.emitDaemonStatus(statusInbound("bogus_state"))
        awaitIo()

        assertEquals(ModeSwitchStatus.Connecting, TransportStatusStore.status.value)
        verify(service, never()).showNotification(true)
    }

    // ── protocol error frame → transient alert (D5) ──────────────

    @Test
    fun `protocol error frame logs and arms transient alert`() {
        service.attachMailboxWsClient(fakeClient)

        fakeClient.emitIncoming(
            MailboxInbound.parse(MailboxFixtures.protocolError()),
        )

        assertTrue("error frame must arm the transient alert", AlertDialogReceiver.alertActive.get())
    }

    // ── reconciliation reads (D4) ─────────────────────────────────

    @Test
    fun `reconcileRegisters is a no-op while mailbox not Connected`() {
        attachWithRouterSync()
        awaitIo()
        assertEquals(MailboxConnectionState.Connecting, fakeClient.connectionState.value)

        service.reconcileRegisters()
        awaitIo()

        assertTrue(fakeClient.sentReads.isEmpty())
    }

    @Test
    fun `GET_ALL_DATA in WS sends resync command and reconciliation reads`() {
        attachWithRouterSync()
        fakeClient.emitState(MailboxConnectionState.Connected)
        awaitIo()

        service.handleGetAllDataWs()
        awaitIo()

        assertTrue("resync command expected", fakeClient.sentCommands.contains("resync"))
        assertEquals(
            "reconciliation reads 01/05/08/03×zones 1..10 expected",
            expectedReconcileReads(),
            fakeClient.sentReads.take(expectedReconcileReads().size),
        )
        assertTrue(fakeClient.sentWrites.isEmpty())
    }

    @Test
    fun `first Connected transition triggers reconciliation reads once per transition`() {
        attachWithRouterSync()

        fakeClient.emitState(MailboxConnectionState.Connected)
        awaitIo()
        val expected = expectedReconcileReads()
        assertEquals("first Connected must trigger reconciliation", expected, fakeClient.sentReads)

        fakeClient.emitState(MailboxConnectionState.Connected)
        awaitIo()
        assertEquals(
            "repeated Connected must not re-trigger",
            expected.size,
            fakeClient.sentReads.size,
        )

        fakeClient.emitState(MailboxConnectionState.Disconnected)
        awaitIo()
        fakeClient.emitState(MailboxConnectionState.Connected)
        awaitIo()
        assertEquals(
            "reconnect must re-trigger reconciliation",
            expected.size * 2,
            fakeClient.sentReads.size,
        )
    }

    // ── outbound wiring ───────────────────────────────────────────

    @Test
    fun `outbound Read action calls sendRead with zone`() {
        attachWithRouterSync()
        fakeClient.emitState(MailboxConnectionState.Connected)
        awaitIo()

        service.dispatchOutboundMailboxActions(
            listOf(OutboundMailboxAction.Read(register = "03", zone = 3)),
        )
        awaitIo()

        assertTrue(
            "read action must call sendRead(register, zone)",
            fakeClient.sentReads.contains("03" to 3),
        )
    }

    @Test
    fun `non-SUCCESS write ack surfaces transient alert`() {
        attachWithRouterSync()
        fakeClient.emitState(MailboxConnectionState.Connected)
        awaitIo()
        fakeClient.nextWriteAck =
            MailboxInbound.Ack(
                msgId = "err",
                status = MailboxAckStatus.ERROR,
                reason = "denied",
                raw =
                    JSONObject()
                        .put("type", MailboxMessageType.ACK)
                        .put("msg_id", "err")
                        .put("status", "error"),
            )

        service.dispatchOutboundMailboxActions(
            listOf(
                OutboundMailboxAction.Write(
                    register = "05",
                    payload =
                        MailboxPayload.Typed(
                            JSONObject().put("power", "on").put("mode", "cool").put("fan", "high"),
                        ),
                ),
            ),
        )
        awaitIo()

        assertTrue("error write ack must arm the transient alert", AlertDialogReceiver.alertActive.get())
    }

    // ── publish gate ──────────────────────────────────────────────

    @Test
    fun `mailbox attached but not yet Connected and deviceOpen false still broadcasts secure rawCan`() {
        service.deviceOpen.set(false)
        service.attachMailboxWsClient(fakeClient)
        // FakeMailboxWsClient starts at Idle and is never moved to Connected here.

        fakeClient.emitIncoming(zoneEventInbound())

        assertTrue(
            "mailbox inbound secure rawCan is not gated by deviceOpen or the mailbox state",
            secureRawCanFrames().isNotEmpty(),
        )
    }

    @Test
    fun `USB broadcastData is unaffected when no mailbox client is attached`() {
        val tag = "getClock"
        service.dataCache.put(tag, tag.toByteArray(Charsets.UTF_8))
        service.deviceOpen.set(true)

        service.broadcastData(tag)

        assertEquals(1, sentMessageFromCb().size)
    }

    @Test
    fun `USB path still requires deviceOpen when mailbox client is attached but not Connected`() {
        service.attachMailboxWsClient(fakeClient)
        service.deviceOpen.set(false)
        val tag = "getClock"
        service.dataCache.put(tag, tag.toByteArray(Charsets.UTF_8))

        service.broadcastData(tag)

        assertTrue(sentMessageFromCb().isEmpty())
    }
}
