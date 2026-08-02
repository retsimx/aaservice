package com.air.advantage.aaservice.service

import android.content.ContextWrapper
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UartForegroundServiceProcessPollResponseTest {

    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        val controller = Robolectric.buildService(UartForegroundService::class.java)
        service = controller.get()

        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
    }

    private fun sentBroadcasts(): List<Intent> =
        shadowOf(RuntimeEnvironment.getApplication() as ContextWrapper).broadcastIntents

    private fun advanceTo(tag: String) {
        val targetIndex = POLL_TAGS.indexOf(tag)
        for (i in 0 until targetIndex) {
            val currentTag = POLL_TAGS[i]
            val base = currentTag.substringBefore("?")
            val payload = if (currentTag == "getSystemData") SYSTEM_DATA
            else "<request>$base</request><dummy>1</dummy>".toByteArray()
            service.dispatchEngine.onFrame(payload)
        }
        assertEquals(targetIndex, service.dispatchEngine.currentPollIndex())
    }

    // ── getSystemData transform ──────────────────────────────────

    @Test
    fun `onFrame getSystemData stores transformed payload`() {
        service.dispatchEngine.onFrame(SYSTEM_DATA)

        val cached = service.dataCache.get("getSystemData")
        assertNotNull(cached)
        val text = String(cached!!, Charsets.UTF_8)
        assertTrue("type injected", text.contains("<type>17</type>"))
        assertTrue("AppStore injected", text.contains("<AppStore>MyAir5</AppStore>"))
        assertTrue("MyAppRev injected", text.contains("<MyAppRev>14.150</MyAppRev>"))
        assertFalse("dhcp range stripped", text.contains("<dhcp>"))
        assertFalse("gateway range stripped", text.contains("<gateway>"))
    }

    @Test
    fun `onFrame getSystemData broadcasts transformed data`() {
        service.deviceOpen.set(true)
        service.dispatchEngine.onFrame(SYSTEM_DATA)

        val sent = sentBroadcasts()
        assertTrue(sent.any {
            it.action == "com.air.advantage.MESSAGE_FROM_CB" &&
                it.getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "getSystemData" &&
                it.getByteArrayExtra("com.air.advantage.MESSAGE_FROM_CB")?.let { bytes ->
                    String(bytes, Charsets.UTF_8).contains("<MyAppRev>14.150</MyAppRev>")
                } == true
        })
    }

    @Test
    fun `onFrame caches data via DataCacheRepository`() {
        service.dispatchEngine.onFrame(SYSTEM_DATA)
        assertNotNull(service.dataCache.get("getSystemData"))
    }

    @Test
    fun `onFrame getSystemData missing MyAppRev is dropped without broadcast`() {
        val payload = ("<request>getSystemData</request><type>00</type><AppStore>x</AppStore>" +
            "<dhcp>192.168.1.1</dhcp><gateway>192.168.1.254</gateway>").toByteArray()
        service.dispatchEngine.onFrame(payload)

        assertNull(service.dataCache.get("getSystemData"))
        val sent = sentBroadcasts()
        assertFalse(sent.any { it.getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "getSystemData" })
    }

    // ── non-system poll tags pass through ────────────────────────

    @Test
    fun `onFrame getClock passes through unchanged`() {
        advanceTo("getClock")
        service.dispatchEngine.onFrame(CLOCK_PAYLOAD)
        assertArrayEquals(CLOCK_PAYLOAD, service.dataCache.get("getClock"))
    }

    @Test
    fun `onFrame getZoneData passes through unchanged`() {
        advanceTo("getZoneData?zone=1")
        val payload = ("<request>getZoneData</request><zone>1</zone><state>off</state>" +
            "<temp>21.0</temp><fan>auto</fan>").toByteArray()
        service.dispatchEngine.onFrame(payload)
        assertArrayEquals(payload, service.dataCache.get("getZoneData?zone=1"))
    }

    @Test
    fun `onFrame identical payload does not re-broadcast`() {
        advanceTo("getClock")
        service.deviceOpen.set(true)
        service.dispatchEngine.onFrame(CLOCK_PAYLOAD)
        service.dispatchEngine.onFrame(CLOCK_PAYLOAD)

        val sent = sentBroadcasts()
        assertEquals(
            1,
            sent.count {
                it.action == "com.air.advantage.MESSAGE_FROM_CB" &&
                    it.getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "getClock"
            }
        )
    }

    @Test
    fun `onFrame mismatched request does not advance or broadcast`() {
        val payload = "<request>getClock</request><time>t</time>".toByteArray()
        service.dispatchEngine.onFrame(payload)

        assertEquals(0, service.dispatchEngine.currentPollIndex())
        assertNull(service.dataCache.get("getClock"))
    }

    @Test
    fun `direct message response is matched and pops the direct queue`() {
        service.enqueueUartMessage("Temperature")
        val sent = service.dispatchEngine.onPing()
        assertNotNull(sent)
        assertTrue(String(sent!!, Charsets.UTF_8).startsWith("<U>Temperature</U="))

        service.dispatchEngine.onFrame("<request>Temperature</request><value>25</value>".toByteArray())

        val next = service.dispatchEngine.onPing()
        assertNotNull(next)
        assertFalse("popped direct message should not be re-sent", String(next!!, Charsets.UTF_8).contains("Temperature"))
    }

    // ── getCAN raw-CAN handling ──────────────────────────────────

    @Test
    fun `onFrame getCAN forwards raw CAN and arms ackCAN`() {
        val payload = "getCAN 1026".toByteArray()
        service.dispatchEngine.onFrame(payload)

        val sent = sentBroadcasts()
        assertTrue(sent.any {
            it.action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" &&
                it.getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "rawCan"
        })

        val ack = service.dispatchEngine.onPing()
        assertNotNull(ack)
        assertTrue(String(ack!!, Charsets.UTF_8).startsWith("<U>ackCAN "))
    }

    @Test
    fun `onFrame getCAN retry-needed suppresses broadcast`() {
        val payload = "getCAN 0000".toByteArray()
        service.dispatchEngine.onFrame(payload)

        assertNull(service.dataCache.get("rawCan"))
        val sent = sentBroadcasts()
        assertFalse(sent.any { it.getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "rawCan" })
    }

    private companion object {
        val POLL_TAGS = listOf(
            "getSystemData",
            "getClock",
            "getZoneData?zone=1",
            "getZoneData?zone=2",
            "getZoneData?zone=3",
            "getZoneData?zone=4",
            "getZoneData?zone=5",
            "getZoneData?zone=6",
            "getZoneData?zone=7",
            "getZoneData?zone=8",
            "getZoneData?zone=9",
            "getZoneData?zone=10"
        )
        val SYSTEM_DATA = ("<request>getSystemData</request><type>00</type><AppStore>x</AppStore>" +
            "<dhcp>192.168.1.1</dhcp><subnet>255.255.255.0</subnet><gateway>192.168.1.254</gateway>" +
            "<MyAppRev>14.148</MyAppRev>").toByteArray(Charsets.UTF_8)
        val CLOCK_PAYLOAD = "<request>getClock</request><time>2026-08-02 12:00:00</time>"
            .toByteArray(Charsets.UTF_8)
    }
}
