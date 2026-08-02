package com.air.advantage.aaservice.service

import android.content.ContextWrapper
import android.content.Intent
import com.air.advantage.aaservice.data.protocol.CrcCalculator
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
class UartForegroundServiceProcessPollTest {

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

    // ── ping → poll → response loop ──────────────────────────────

    @Test
    fun `onPing returns framed current poll tag without advancing`() {
        val frame = service.dispatchEngine.onPing()
        assertNotNull(frame)
        val expectedCrc = CrcCalculator.computeHex("getSystemData")
        assertEquals("<U>getSystemData</U=$expectedCrc>", String(frame!!, Charsets.UTF_8))
        assertEquals(0, service.dispatchEngine.currentPollIndex())
    }

    @Test
    fun `matching response advances poll index`() {
        val engine = service.dispatchEngine
        engine.onPing()
        engine.onFrame(SYSTEM_DATA)
        assertEquals(1, engine.currentPollIndex())
    }

    @Test
    fun `mismatched response does not advance poll index`() {
        val engine = service.dispatchEngine
        engine.onPing()
        engine.onFrame("<request>getClock</request><time>t</time>".toByteArray())
        assertEquals(0, engine.currentPollIndex())
    }

    @Test
    fun `poll index wraps to zero after last tag`() {
        val engine = service.dispatchEngine
        POLL_TAGS.forEachIndexed { i, tag ->
            assertEquals(i, engine.currentPollIndex())
            val base = tag.substringBefore("?")
            val payload = if (tag == "getSystemData") SYSTEM_DATA
            else "<request>$base</request><dummy>1</dummy>".toByteArray()
            engine.onFrame(payload)
        }
        assertEquals(0, engine.currentPollIndex())
    }

    @Test
    fun `ping poll response loop caches and broadcasts transformed data`() {
        service.deviceOpen.set(true)
        val engine = service.dispatchEngine
        engine.onPing()
        engine.onFrame(SYSTEM_DATA)

        val cached = service.dataCache.get("getSystemData")
        assertNotNull(cached)
        assertTrue(String(cached!!, Charsets.UTF_8).contains("<type>17</type>"))
        val sent = sentBroadcasts()
        assertTrue(sent.any { it.getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "getSystemData" })
    }

    @Test
    fun `direct message is sent before poll on next ping`() {
        service.enqueueUartMessage("Temperature")
        val frame = service.dispatchEngine.onPing()
        assertNotNull(frame)
        val expectedCrc = CrcCalculator.computeHex("Temperature")
        assertEquals("<U>Temperature</U=$expectedCrc>", String(frame!!, Charsets.UTF_8))
        assertEquals(0, service.dispatchEngine.currentPollIndex())
    }

    // ── CAN-pending path ─────────────────────────────────────────

    @Test
    fun `CAN pending returns setCAN frame before poll`() {
        val engine = service.dispatchEngine
        service.enqueueCanIds("1 2 3 4 5")
        engine.onPing()
        val frame = engine.onPing()
        assertNotNull(frame)
        val text = String(frame!!, Charsets.UTF_8)
        assertTrue(text.startsWith("<U>setCAN "))
        assertTrue(text.contains("1 2 3 4 5"))
        assertFalse(text.contains("getSystemData"))
    }

    @Test
    fun `setCAN reply advances CAN queue ids`() {
        val engine = service.dispatchEngine
        service.enqueueCanIds("1 2 3 4 5 6 7 8 9 10")
        engine.onPing()
        val first = engine.onPing()
        val second = engine.onPing()
        assertNotNull(first)
        assertNotNull(second)
        val firstText = String(first!!, Charsets.UTF_8)
        assertTrue(firstText.startsWith("<U>setCAN "))
        // second ping after the CAN send arms the poll again
        assertTrue(String(second!!, Charsets.UTF_8).contains("getSystemData"))
    }

    // ── data cache ───────────────────────────────────────────────

    @Test
    fun `dataCache stores and retrieves data correctly`() {
        val tag = "getSystemData"
        val data = "type=17;AppStore=MyAir5".toByteArray()

        service.dataCache.put(tag, data)

        val cached = service.dataCache.get(tag)
        assertNotNull(cached)
        assertArrayEquals(data, cached)
    }

    @Test
    fun `dataCache hasChanged returns true for new tag`() {
        val tag = "getZoneData?zone=1"
        val data = "temp=22".toByteArray()

        assertTrue(service.dataCache.hasChanged(tag, data))
    }

    @Test
    fun `dataCache hasChanged returns false for unchanged data`() {
        val tag = "getTimers"
        val data = "timer1=on".toByteArray()

        service.dataCache.put(tag, data)
        assertFalse(service.dataCache.hasChanged(tag, data))
    }

    // ── PollQueueRepository ──────────────────────────────────────

    @Test
    fun `pollQueue returns correct entry after initialization`() {
        service.pollQueue.initialize(isMyAir5 = true)
        val current = service.pollQueue.currentPoll()

        assertNotNull(current)
        assertEquals("getSystemData", current?.tag)
    }

    @Test
    fun `pollQueue advanceToNext moves to next entry`() {
        service.pollQueue.initialize(isMyAir5 = true)
        service.pollQueue.advanceToNext()
        val current = service.pollQueue.currentPoll()

        assertNotNull(current)
        assertEquals("getClock", current?.tag)
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
    }
}
