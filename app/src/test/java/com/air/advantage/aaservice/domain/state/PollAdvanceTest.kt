package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollAdvanceTest {

    private val sink = RecordingSink()
    private val typeBytes = "17".toByteArray(Charsets.UTF_8)
    private val appStoreBytes = "MyAir5".toByteArray(Charsets.UTF_8)

    private val pollTags = listOf("getSystemData", "getClock", "getZoneData?zone=1")

    private fun engine(tags: List<String> = pollTags): UartDispatchEngine =
        UartDispatchEngine(tags, typeBytes, appStoreBytes, sink)

    private fun frameOf(content: String): String =
        "<U>$content</U=${CrcCalculator.computeHex(content)}>"

    private fun systemDataPayload(): ByteArray =
        ("<request>getSystemData</request><type>00</type><AppStore>x</AppStore>" +
            "<dhcp>192.168.1.1</dhcp><subnet>255.255.255.0</subnet>" +
            "<gateway>192.168.1.254</gateway><MyAppRev>14.148</MyAppRev>").toByteArray(Charsets.UTF_8)

    private fun clockPayload(): ByteArray =
        "<request>getClock</request><clock>12:00</clock>".toByteArray(Charsets.UTF_8)

    private fun zonePayload(): ByteArray =
        "<request>getZoneData</request><zone>1</zone>".toByteArray(Charsets.UTF_8)

    // --- ping never advances ---

    @Test
    fun `pings never advance poll index`() {
        val e = engine()
        repeat(20) { e.onPing() }
        assertEquals(0, e.currentPollIndex())
    }

    // --- mismatched request ---

    @Test
    fun `mismatched request does not advance index`() {
        val e = engine()
        e.onFrame("<request>getClock</request>".toByteArray(Charsets.UTF_8))
        assertEquals(0, e.currentPollIndex())
        assertTrue(sink.pollData.isEmpty())
    }

    @Test
    fun `mismatched request does not advance index after prior match`() {
        val e = engine()
        e.onFrame(systemDataPayload())
        assertEquals(1, e.currentPollIndex())
        e.onFrame("<request>getZoneData</request>".toByteArray(Charsets.UTF_8))
        assertEquals(1, e.currentPollIndex())
    }

    // --- matching request advances ---

    @Test
    fun `matching request advances index and wraps at end`() {
        val e = engine()
        e.onFrame(systemDataPayload())
        assertEquals(1, e.currentPollIndex())
        e.onFrame(clockPayload())
        assertEquals(2, e.currentPollIndex())
        e.onFrame(zonePayload())
        assertEquals(0, e.currentPollIndex())
    }

    @Test
    fun `wrapping poll index reallows CAN`() {
        val e = engine(listOf("getSystemData", "getClock"))
        e.onFrame("<ack>0</ack><request>Unknown</request>".toByteArray(Charsets.UTF_8))
        e.onFrame(systemDataPayload())
        e.onFrame(clockPayload()) // wrap to 0, canUnsupported cleared
        assertEquals(0, e.currentPollIndex())
        e.enqueueCanIds(listOf("5"))
        e.onPing() // poll entry re-arms canWanted
        val frame = String(e.onPing()!!, Charsets.UTF_8)
        assertTrue(frame.startsWith("<U>setCAN "))
    }

    // --- getSystemData transform + cache ---

    @Test
    fun `getSystemData transformed and delivered with transformed payload`() {
        val e = engine(listOf("getSystemData"))
        e.onFrame(systemDataPayload())
        assertEquals(1, sink.pollData.size)
        assertEquals("getSystemData", sink.pollData[0].first)
        assertEquals(
            "<request>getSystemData</request><type>17</type><AppStore>MyAir5</AppStore>" +
                "<MyAppRev>14.150</MyAppRev>",
            String(sink.pollData[0].second, Charsets.UTF_8)
        )
    }

    @Test
    fun `null transform drops the response`() {
        val e = engine(listOf("getSystemData"))
        e.onFrame("<request>getSystemData</request><type>00</type>".toByteArray(Charsets.UTF_8))
        assertTrue(sink.pollData.isEmpty())
    }

    @Test
    fun `unchanged response is not rebroadcast`() {
        val e = engine(listOf("getSystemData"))
        e.onFrame(systemDataPayload())
        e.onFrame(systemDataPayload())
        assertEquals(1, sink.pollData.size)
    }

    @Test
    fun `changed response is rebroadcast`() {
        val e = engine(listOf("getClock"))
        e.onFrame("<request>getClock</request><clock>12:00</clock>".toByteArray(Charsets.UTF_8))
        e.onFrame("<request>getClock</request><clock>12:01</clock>".toByteArray(Charsets.UTF_8))
        assertEquals(2, sink.pollData.size)
    }

    @Test
    fun `reset clears poll index and response cache`() {
        val e = engine(listOf("getSystemData", "getClock"))
        e.onFrame(systemDataPayload())
        assertEquals(1, e.currentPollIndex())
        assertEquals(1, sink.pollData.size)
        e.reset()
        assertEquals(0, e.currentPollIndex())
        e.onFrame(systemDataPayload())
        assertEquals(2, sink.pollData.size)
    }

    @Test
    fun `non systemData poll is delivered raw`() {
        val e = engine(listOf("getClock"))
        val payload = "<request>getClock</request><clock>12:00</clock>".toByteArray(Charsets.UTF_8)
        e.onFrame(payload)
        assertEquals(1, sink.pollData.size)
        assertEquals("getClock", sink.pollData[0].first)
        assertArrayEquals(payload, sink.pollData[0].second)
    }

    // --- CAN2 in use does not advance ---

    @Test
    fun `CAN2 in use does not advance index`() {
        val e = engine()
        e.onPing() // poll entry sent
        e.onFrame("CAN2 in use".toByteArray(Charsets.UTF_8))
        assertEquals(0, e.currentPollIndex())
    }

    // --- getCAN does not advance ---

    @Test
    fun `getCAN does not advance poll index`() {
        val e = engine()
        e.onFrame("getCAN 12345".toByteArray(Charsets.UTF_8))
        assertEquals(0, e.currentPollIndex())
        assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
    }

    private class RecordingSink : UartEventSink {
        val pollData = mutableListOf<Pair<String, ByteArray>>()
        val rawCan = mutableListOf<ByteArray>()

        override fun onPollData(tag: String, payload: ByteArray) {
            pollData.add(tag to payload)
        }

        override fun onRawCan(payload: ByteArray) {
            rawCan.add(payload)
        }
    }
}
