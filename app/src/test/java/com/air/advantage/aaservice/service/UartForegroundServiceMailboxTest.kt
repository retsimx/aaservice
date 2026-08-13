package com.air.advantage.aaservice.service

import android.content.Intent
import com.air.advantage.aaservice.data.mailbox.FakeMailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxConnectionState
import com.air.advantage.aaservice.data.mailbox.MailboxFixtures
import com.air.advantage.aaservice.data.mailbox.MailboxInbound
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A4 (#51) service wiring: [UartForegroundService.attachMailboxWsClient] /
 * [UartForegroundService.onMailboxInbound] map `mailbox_snapshot` → secure rawCan
 * (USB cold-start parity) and `mailbox_event` → `MESSAGE_FROM_CB` poll-tag updates,
 * plus the `deviceOpen || mailbox Connected` publish gate in
 * [UartForegroundService.broadcastData] (design `41-mailbox-to-message-from-cb.md` §6).
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
    }

    private fun sentMessageFromCb(): List<Intent> =
        capturedIntents.filter { it.action == "com.air.advantage.MESSAGE_FROM_CB" }

    private fun snapshotInbound(): MailboxInbound.Snapshot =
        MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot

    private fun brokerSnapshotInbound(): MailboxInbound.Snapshot =
        MailboxInbound.parse(MailboxFixtures.load("mailbox/mailbox_snapshot_broker.json"))
            as MailboxInbound.Snapshot

    private fun zoneEventInbound(): MailboxInbound.Event =
        MailboxInbound.parse(MailboxFixtures.event()) as MailboxInbound.Event

    // ── snapshot → secure rawCan only (USB cold-start parity) ────

    @Test
    fun `snapshot does not emit MESSAGE_FROM_CB poll tags`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(snapshotInbound())

        assertTrue(
            "snapshot must not flood MESSAGE_FROM_CB (USB cold-start = rawCan only)",
            sentMessageFromCb().isEmpty(),
        )
    }

    @Test
    fun `snapshot emits MESSAGE_FROM_CB_SECURE rawCan re-encoded from typed registers`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(brokerSnapshotInbound())

        val secure = capturedIntents.filter {
            it.action == "com.air.advantage.MESSAGE_FROM_CB_SECURE"
        }
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
    fun `snapshot caches poll payloads by tag without broadcasting them`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(snapshotInbound())

        assertNotNull(service.dataCache.get("getSystemData"))
        assertNotNull(service.dataCache.get("getZoneData?zone=1"))
        assertNotNull(service.dataCache.get("getZoneData?zone=2"))
        assertTrue(sentMessageFromCb().isEmpty())
    }

    @Test
    fun `duplicate snapshot still skips MESSAGE_FROM_CB poll tags`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(brokerSnapshotInbound())
        fakeClient.emitIncoming(brokerSnapshotInbound())

        assertTrue(sentMessageFromCb().isEmpty())
        assertTrue(
            capturedIntents.any { it.action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" },
        )
    }

    // ── mailbox_event → incremental update, no restart ───────────

    @Test
    fun `mailbox_event updates propagate without restart`() {
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(snapshotInbound())
        fakeClient.emitIncoming(zoneEventInbound())

        val zoneBroadcasts = sentMessageFromCb().filter {
            it.getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "getZoneData?zone=1"
        }
        assertTrue(
            "incremental event broadcasts the zone tag, got ${zoneBroadcasts.size}",
            zoneBroadcasts.size >= 1
        )

        val latestXml = String(
            zoneBroadcasts.last().getByteArrayExtra("com.air.advantage.MESSAGE_FROM_CB")!!,
            Charsets.UTF_8
        )
        // mailbox_event.json fixture: zone_id=1, open=true, damper_pct=80, measured_temp_c=23.4
        assertTrue("event field applied", latestXml.contains("<damper>80</damper>"))
        assertTrue("event field applied", latestXml.contains("<measuredTemp>23.4</measuredTemp>"))
        // sensor_type/target_temp_c are only in the original snapshot, not this sparse event —
        // merge-onto-cache must preserve them rather than rebuilding from scratch.
        assertTrue("cache-only field preserved by merge", latestXml.contains("<sensor>temp</sensor>"))
        assertTrue("cache-only field preserved by merge", latestXml.contains("<temp>22.5</temp>"))

        // No onCreate/onDestroy round trip happened between snapshot and event.
        assertEquals(service, UartForegroundService.instance)
    }

    // ── publish gate ──────────────────────────────────────────────

    @Test
    fun `deviceOpen false but mailbox Connected still broadcasts mailbox path`() {
        service.deviceOpen.set(false)
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)

        fakeClient.emitIncoming(zoneEventInbound())

        assertFalse(service.deviceOpen.get())
        assertTrue("mailbox-ready broadcast should still fire", sentMessageFromCb().isNotEmpty())
    }

    @Test
    fun `mailbox attached but not yet Connected and deviceOpen false suppresses broadcast`() {
        service.deviceOpen.set(false)
        service.attachMailboxWsClient(fakeClient)
        // FakeMailboxWsClient starts at Idle and is never moved to Connected here.

        fakeClient.emitIncoming(zoneEventInbound())

        assertNotNull(
            "mapped poll is still cached even when not yet broadcast",
            service.dataCache.get("getZoneData?zone=1")
        )
        assertTrue(
            "no broadcast while neither deviceOpen nor mailbox-ready",
            sentMessageFromCb().isEmpty()
        )
    }

    @Test
    fun `mailbox Disconnected after being Connected falls back to requiring deviceOpen`() {
        service.deviceOpen.set(false)
        service.attachMailboxWsClient(fakeClient)
        fakeClient.emitState(MailboxConnectionState.Connected)
        fakeClient.emitIncoming(zoneEventInbound())
        assertTrue(sentMessageFromCb().isNotEmpty())
        capturedIntents.clear()

        fakeClient.emitState(MailboxConnectionState.Disconnected)
        fakeClient.emitIncoming(zoneEventInbound())

        assertTrue(
            "no broadcast once mailbox drops out of Connected and USB is still closed",
            sentMessageFromCb().isEmpty()
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
