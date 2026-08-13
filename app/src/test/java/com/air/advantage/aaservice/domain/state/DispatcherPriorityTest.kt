package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcherPriorityTest {
    private val sink = RecordingSink()
    private val typeBytes = "17".toByteArray(Charsets.UTF_8)
    private val appStoreBytes = "MyAir5".toByteArray(Charsets.UTF_8)

    private fun engine(pollTags: List<String> = listOf("getClock")): UartDispatchEngine =
        UartDispatchEngine(pollTags, typeBytes, appStoreBytes, sink)

    private fun frameOf(content: String): String = "<U>$content</U=${CrcCalculator.computeHex(content)}>"

    private fun contentOf(frame: String): String = frame.removePrefix("<U>").substringBefore("</U=")

    // --- ackCAN priority ---

    @Test
    fun `ackCAN armed beats CAN direct and poll`() {
        val e = engine()
        e.setCrcOk(true)
        e.onFrame("getCAN 12345".toByteArray(Charsets.UTF_8))
        e.enqueueCanIds(listOf("1", "2"))
        e.enqueueDirectMessage("setPoint?zone=1")
        assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `ackCAN reports zero when lastCrcOk is false`() {
        val e = engine()
        e.setCrcOk(false)
        e.onFrame("getCAN 12345".toByteArray(Charsets.UTF_8))
        assertEquals(frameOf("ackCAN 0"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `valid getCAN arms ackCAN and broadcasts raw CAN`() {
        val e = engine()
        val payload = "getCAN 12345".toByteArray(Charsets.UTF_8)
        e.onFrame(payload)
        assertEquals(1, sink.rawCan.size)
        assertArrayEquals(payload, sink.rawCan[0])
        assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `getCAN retry needed sets canRetry without broadcasting`() {
        val e = engine()
        e.onFrame("getCAN 0000".toByteArray(Charsets.UTF_8))
        assertTrue(sink.rawCan.isEmpty())
        // ackCAN is still armed by the inbound getCAN
        assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `getCAN retry broadcasts after three retries`() {
        val e = engine()
        val payload = "getCAN 0000".toByteArray(Charsets.UTF_8)
        repeat(3) { e.onFrame(payload) }
        assertTrue(sink.rawCan.isEmpty())
        e.onFrame(payload)
        assertEquals(1, sink.rawCan.size)
        assertArrayEquals(payload, sink.rawCan[0])
    }

    // --- CAN priority ---

    @Test
    fun `CAN wanted builds setCAN frame with at most 25 ids`() {
        val e = engine()
        e.enqueueCanIds((1..30).map { it.toString() })
        e.onPing() // prime canWanted via poll entry
        val frame = String(e.onPing()!!, Charsets.UTF_8)
        assertTrue(frame.startsWith("<U>setCAN "))
        val ids = contentOf(frame).removePrefix("setCAN ").split(" ").map { it.toInt() }
        assertEquals(25, ids.size)
        assertEquals((1..25).toList(), ids)
    }

    @Test
    fun `retry path resends stored setCAN frame`() {
        val e = engine()
        e.enqueueCanIds((1..30).map { it.toString() })
        e.onPing() // prime canWanted
        val first = String(e.onPing()!!, Charsets.UTF_8)
        assertTrue(first.startsWith("<U>setCAN "))
        e.onFrame("getCAN 0000".toByteArray(Charsets.UTF_8)) // retry-needed
        assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
        e.onPing() // poll re-arms canWanted
        assertEquals(first, String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `CAN beats direct and poll`() {
        val e = engine()
        e.enqueueCanIds(listOf("7"))
        e.enqueueDirectMessage("setPoint?zone=1")
        e.onPing() // prime canWanted via direct entry
        val frame = String(e.onPing()!!, Charsets.UTF_8)
        assertTrue(frame.startsWith("<U>setCAN "))
        assertEquals(0, e.currentPollIndex())
    }

    // --- direct queue priority ---

    @Test
    fun `direct beats poll`() {
        val e = engine()
        e.enqueueDirectMessage("setPoint?zone=1")
        assertEquals(frameOf("setPoint?zone=1"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `direct queue resends head then drops after three identical sends`() {
        val e = engine()
        e.enqueueCanIds((1..100).map { it.toString() })
        e.enqueueDirectMessage("setPoint?zone=1")
        val expected = frameOf("setPoint?zone=1")
        assertEquals(expected, String(e.onPing()!!, Charsets.UTF_8))
        e.onPing() // CAN branch resends stored setCAN
        assertEquals(expected, String(e.onPing()!!, Charsets.UTF_8))
        e.onPing()
        assertEquals(expected, String(e.onPing()!!, Charsets.UTF_8))
        e.onPing()
        assertNull(e.onPing()) // dropped after 3 identical
        e.onPing() // CAN branch
        assertEquals(frameOf("getClock"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `direct queue falls through to poll after 15 stuck sends`() {
        val e = engine()
        e.enqueueCanIds((1..400).map { it.toString() })
        for (i in 1..15) {
            e.enqueueDirectMessage("msg$i")
            assertEquals(frameOf("msg$i"), String(e.onPing()!!, Charsets.UTF_8))
            e.onFrame("<request>msg$i</request>".toByteArray(Charsets.UTF_8))
            e.onPing() // CAN branch resends stored setCAN
        }
        e.enqueueDirectMessage("msg16")
        assertEquals(frameOf("getClock"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `matching direct response pops queue head`() {
        val e = engine()
        e.enqueueDirectMessage("setPoint?zone=1")
        e.onPing() // direct entry sent
        e.onFrame("<request>setPoint</request><value>22</value>".toByteArray(Charsets.UTF_8))
        e.onPing() // CAN branch (canWanted true)
        assertEquals(frameOf("getClock"), String(e.onPing()!!, Charsets.UTF_8))
    }

    // --- CAN2 in use ---

    @Test
    fun `CAN2 in use via poll mismatch sets canInUse and drains queued CAN`() {
        val e = engine()
        e.enqueueCanIds(listOf("9"))
        e.onPing() // poll entry sent (arms canWanted because queue non-empty)
        e.onFrame("CAN2 in use".toByteArray(Charsets.UTF_8))
        val frame = String(e.onPing()!!, Charsets.UTF_8)
        assertTrue(frame.startsWith("<U>setCAN"))
        assertTrue(frame.contains("9"))
        assertEquals(0, e.currentPollIndex())
    }

    @Test
    fun `CAN2 in use with empty queues skips empty setCAN and keeps polling`() {
        val e = engine()
        e.onPing() // poll — queues empty so canWanted is not armed
        e.onFrame("CAN2 in use".toByteArray(Charsets.UTF_8))
        // canInUse true but nothing to send → fall through to poll again
        assertEquals(frameOf("getClock"), String(e.onPing()!!, Charsets.UTF_8))
        assertEquals(0, e.currentPollIndex())
    }

    @Test
    fun `CAN2 in use via direct response drains queued CAN`() {
        val e = engine()
        e.enqueueCanIds(listOf("9"))
        e.enqueueDirectMessage("setPoint?zone=1")
        e.onPing() // direct entry sent
        e.onFrame("CAN2 in use".toByteArray(Charsets.UTF_8))
        val frame = String(e.onPing()!!, Charsets.UTF_8)
        assertTrue(frame.startsWith("<U>setCAN"))
        assertTrue(frame.contains("9"))
    }

    // --- poll entry ---

    @Test
    fun `poll sends current poll frame without advancing index`() {
        val e = engine(listOf("getSystemData", "getClock"))
        assertEquals(frameOf("getSystemData"), String(e.onPing()!!, Charsets.UTF_8))
        assertEquals(0, e.currentPollIndex())
    }

    private class RecordingSink : UartEventSink {
        val pollData = mutableListOf<Pair<String, ByteArray>>()
        val rawCan = mutableListOf<ByteArray>()

        override fun onPollData(
            tag: String,
            payload: ByteArray,
        ) {
            pollData.add(tag to payload)
        }

        override fun onRawCan(payload: ByteArray) {
            rawCan.add(payload)
        }
    }
}
