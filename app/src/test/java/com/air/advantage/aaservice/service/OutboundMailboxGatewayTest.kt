package com.air.advantage.aaservice.service

import android.content.Intent
import androidx.preference.PreferenceManager
import com.air.advantage.aaservice.data.mailbox.FakeMailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxAckStatus
import com.air.advantage.aaservice.data.mailbox.MailboxConnectionState
import com.air.advantage.aaservice.data.mailbox.MailboxInbound
import com.air.advantage.aaservice.data.mailbox.MailboxMessageType
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.data.mailbox.MyAir5OutboundMailboxMapper
import com.air.advantage.aaservice.data.mailbox.OutboundMailboxAction
import com.air.advantage.aaservice.data.mailbox.ReadOutcome
import com.air.advantage.aaservice.receiver.AlertDialogReceiver
import com.air.advantage.aaservice.receiver.GetAllDataReceiver
import com.air.advantage.aaservice.receiver.MessageToCbReceiver
import com.air.advantage.aaservice.util.PreferencesManager
import com.air.advantage.aaservice.util.TransportMode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * A5 gateway: WS outbound mapping via [UartForegroundService] + [FakeMailboxWsClient].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OutboundMailboxGatewayTest {

    private lateinit var controller: org.robolectric.android.controller.ServiceController<UartForegroundService>
    private lateinit var service: UartForegroundService
    private lateinit var prefs: PreferencesManager
    private lateinit var fakeWs: FakeMailboxWsClient

    @Before
    fun setUp() {
        controller = Robolectric.buildService(UartForegroundService::class.java)
        service = controller.create().get()
        UartForegroundService.instance = service
        AlertDialogReceiver.alertActive.set(false)
        PreferenceManager.getDefaultSharedPreferences(service).edit().clear().apply()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
        AlertDialogReceiver.alertActive.set(false)
    }

    private fun injectWsConnected() {
        prefs = PreferencesManager(service)
        prefs.transportMode = TransportMode.Ws
        fakeWs = FakeMailboxWsClient()
        service.preferencesManager = prefs
        service.mailboxWsClientFactory = MailboxWsClientFactory { fakeWs }
        service.ensureTransportRouter().applyMode(TransportMode.Ws)
        fakeWs.emitState(MailboxConnectionState.Connected)
    }

    private fun injectUsb() {
        prefs = PreferencesManager(service)
        prefs.transportMode = TransportMode.Usb
        fakeWs = FakeMailboxWsClient()
        service.preferencesManager = prefs
        service.mailboxWsClientFactory = MailboxWsClientFactory { fakeWs }
        service.ensureTransportRouter().applyMode(TransportMode.Usb)
    }

    private fun awaitOutbound() {
        // ioScope uses Dispatchers.IO — give the serialized send a moment.
        TimeUnit.MILLISECONDS.sleep(300)
    }

    @Test
    fun `WS setAircon power sends write system_status`() {
        injectWsConnected()
        service.deviceOpen.set(false)

        val msg = """setAircon?json={"aircons":{"ac1":{"info":{"state":"on","mode":"cool","fan":"high"}}}}"""
        service.enqueueUartMessage(msg)
        awaitOutbound()

        assertEquals(1, fakeWs.sentWrites.size)
        assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, fakeWs.sentWrites[0].first)
        assertNull(fakeWs.sentWrites[0].third)
        assertEquals("on", fakeWs.sentWrites[0].second.getString("power"))
        assertEquals("cool", fakeWs.sentWrites[0].second.getString("mode"))
        assertEquals("high", fakeWs.sentWrites[0].second.getString("fan"))
    }

    @Test
    fun `WS setAircon zone sends write zone_state with zone address`() {
        injectWsConnected()
        val msg = """setAircon?json={"aircons":{"ac1":{"zones":{"z03":{"state":"open","setTemp":22}}}}}"""
        service.enqueueUartMessage(msg)
        awaitOutbound()

        assertEquals(1, fakeWs.sentWrites.size)
        assertEquals(MyAir5OutboundMailboxMapper.REG_ZONE_STATE, fakeWs.sentWrites[0].first)
        assertEquals(3, fakeWs.sentWrites[0].third)
        assertTrue(fakeWs.sentWrites[0].second.getBoolean("open"))
        assertEquals(22.0, fakeWs.sentWrites[0].second.getDouble("target_temp_c"), 0.001)
        // zone is an address field (B-4 broker surface), never in the payload
        assertTrue(!fakeWs.sentWrites[0].second.has("zone_id"))
        assertTrue(!fakeWs.sentWrites[0].second.has("zone"))
    }

    @Test
    fun `WS ack error does not pretend success`() {
        injectWsConnected()
        fakeWs.nextWriteAck = MailboxInbound.Ack(
            msgId = "err",
            status = MailboxAckStatus.ERROR,
            reason = "denied",
            raw = JSONObject()
                .put("type", MailboxMessageType.ACK)
                .put("msg_id", "err")
                .put("status", "error"),
        )
        service.enqueueUartMessage(
            """setAircon?json={"aircons":{"ac1":{"info":{"state":"off"}}}}""",
        )
        awaitOutbound()
        // Send still attempted (recorded); status is error — gateway must not throw/crash.
        assertEquals(1, fakeWs.sentWrites.size)
    }

    @Test
    fun `WS outbound Read action calls sendRead with register and zone`() {
        injectWsConnected()

        service.dispatchOutboundMailboxActions(
            listOf(OutboundMailboxAction.Read(register = "03", zone = 3)),
        )
        awaitOutbound()

        assertEquals(1, fakeWs.sentReads.size)
        assertEquals("03" to 3, fakeWs.sentReads[0])
        assertTrue(fakeWs.sentWrites.isEmpty())
    }

    @Test
    fun `WS read error outcome logs and arms transient alert`() {
        injectWsConnected()
        fakeWs.nextReadOutcome = ReadOutcome.Error(
            MailboxInbound.Ack(
                msgId = "err-read",
                status = MailboxAckStatus.ERROR,
                reason = "register 03 has no value",
                raw = JSONObject()
                    .put("type", MailboxMessageType.ACK)
                    .put("msg_id", "err-read")
                    .put("status", "error"),
            ),
        )

        service.dispatchOutboundMailboxActions(
            listOf(OutboundMailboxAction.Read(register = "03", zone = 1)),
        )
        awaitOutbound()

        assertEquals(1, fakeWs.sentReads.size)
        assertTrue("read error outcome must arm the transient alert", AlertDialogReceiver.alertActive.get())
    }

    @Test
    fun `USB mode does not call WS sendWrite`() {
        injectUsb()
        service.deviceOpen.set(true)
        service.enqueueUartMessage("setZoneData?zone=1")
        awaitOutbound()
        assertTrue(fakeWs.sentWrites.isEmpty())
        assertTrue(fakeWs.sentCommands.isEmpty())
    }

    @Test
    fun `WS GET_ALL_DATA triggers resync command`() {
        injectWsConnected()
        service.handleGetAllDataWs()
        awaitOutbound()
        assertEquals(1, fakeWs.sentCommands.size)
        assertTrue(fakeWs.sentWrites.isEmpty())
    }

    @Test
    fun `WS GET_ALL_DATA does not rebroadcast MESSAGE_FROM_CB poll tags`() {
        injectWsConnected()
        service.attachMailboxWsClient(fakeWs)
        fakeWs.emitState(MailboxConnectionState.Connected)
        service.dataCache.put("getSystemData", """<systemData/>""".toByteArray())
        service.dataCache.put("getZoneData?zone=1", """<zoneData/>""".toByteArray())

        service.handleGetAllDataWs()
        awaitOutbound()

        val pollTags = org.robolectric.Shadows.shadowOf(service).broadcastIntents
            .filter { it.action == "com.air.advantage.MESSAGE_FROM_CB" }
        assertTrue(
            "WS GetAllData must not flood poll XML (USB cold-start parity), got ${pollTags.size}",
            pollTags.isEmpty(),
        )
        assertEquals(1, fakeWs.sentCommands.size)
    }

    @Test
    fun `WS GET_ALL_DATA rebroadcasts cached rawCan before resync`() {
        injectWsConnected()
        service.attachMailboxWsClient(fakeWs)
        fakeWs.emitState(MailboxConnectionState.Connected)
        // Seed lastRawCan the same way a prior mailbox snapshot would.
        service.handleGetCan("getCAN 1 0703181f30a00000000000000")
        org.robolectric.Shadows.shadowOf(service).broadcastIntents.clear()

        service.handleGetAllDataWs()
        awaitOutbound()

        val secure = org.robolectric.Shadows.shadowOf(service).broadcastIntents
            .filter { it.action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" }
        assertTrue("expected forced rawCan rebroadcast", secure.isNotEmpty())
        assertEquals(1, fakeWs.sentCommands.size)
    }

    @Test
    fun `WS normal CAN ids do not send`() {
        injectWsConnected()
        service.processCanIds("5 6 7")
        awaitOutbound()
        assertTrue(fakeWs.sentWrites.isEmpty())
        assertTrue(fakeWs.sentCommands.isEmpty())
    }

    @Test
    fun `WS reg06 flush triggers resync command`() {
        injectWsConnected()
        service.processCanIds(MyAir5OutboundMailboxMapper.REG06_FLUSH_TOKEN)
        awaitOutbound()
        assertEquals(1, fakeWs.sentCommands.size)
    }

    @Test
    fun `MessageToCbReceiver WS reaches gateway when deviceOpen false`() {
        injectWsConnected()
        service.deviceOpen.set(false)
        val receiver = MessageToCbReceiver()
        val intent = Intent("com.air.advantage.MESSAGE_TO_CB").putExtra(
            "com.air.advantage.MESSAGE_TO_CB",
            """setAircon?json={"aircons":{"ac1":{"info":{"state":"on"}}}}""",
        )
        receiver.onReceive(service, intent)
        awaitOutbound()
        assertEquals(1, fakeWs.sentWrites.size)
    }

    @Test
    fun `GetAllDataReceiver WS triggers resync without deviceOpen`() {
        injectWsConnected()
        service.deviceOpen.set(false)
        GetAllDataReceiver().onReceive(service, Intent("com.air.advantage.GET_ALL_DATA"))
        awaitOutbound()
        assertEquals(1, fakeWs.sentCommands.size)
    }
}
