package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the direct-queue behavior of [UartDispatchEngine] against the reference
 * `ServiceUart$k.d()` (reference/sources/com/air/advantage/aaservice/ServiceUart.java).
 *
 * The direct queue sits below ackCAN and CAN in onPing() priority but above the poll
 * list. Each direct send also re-arms canWanted, so the ping after a direct send emits a
 * setCAN frame; the tests drive that CAN branch explicitly so the direct-queue assertions
 * stay deterministic.
 */
class DirectQueueTest {

    private val sink = RecordingSink()
    private val typeBytes = "17".toByteArray(Charsets.UTF_8)
    private val appStoreBytes = "MyAir5".toByteArray(Charsets.UTF_8)

    private fun engine(pollTags: List<String> = listOf("getClock")): UartDispatchEngine =
        UartDispatchEngine(pollTags, typeBytes, appStoreBytes, sink)

    private fun frameOf(content: String): String =
        "<U>$content</U=${CrcCalculator.computeHex(content)}>"

    @Test
    fun `direct queue delivers FIFO ahead of poll`() {
        val e = engine()
        e.enqueueCanIds((1..200).toList())
        e.enqueueDirectMessage("a?zone=1")
        e.enqueueDirectMessage("b?zone=1")

        assertEquals(frameOf("a?zone=1"), String(e.onPing()!!, Charsets.UTF_8))

        e.onFrame("<request>a</request>".toByteArray(Charsets.UTF_8))
        e.onPing() // CAN branch consumes the canWanted re-armed by the direct send
        assertEquals(frameOf("b?zone=1"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `direct queue beats poll`() {
        val e = engine()
        e.enqueueDirectMessage("setPoint?zone=1")
        assertEquals(frameOf("setPoint?zone=1"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `same direct message is dropped after three identical sends`() {
        val e = engine()
        e.enqueueCanIds((1..200).toList())
        e.enqueueDirectMessage("setPoint?zone=1")
        val expected = frameOf("setPoint?zone=1")

        assertEquals(expected, String(e.onPing()!!, Charsets.UTF_8)) // 1st identical
        e.onPing() // CAN branch
        assertEquals(expected, String(e.onPing()!!, Charsets.UTF_8)) // 2nd identical
        e.onPing() // CAN branch
        assertEquals(expected, String(e.onPing()!!, Charsets.UTF_8)) // 3rd identical
        e.onPing() // CAN branch
        assertNull("head must be dropped after 3 identical sends", e.onPing())

        // direct message is gone: only setCAN / poll remain
        e.onPing() // CAN branch
        assertEquals(frameOf("getClock"), String(e.onPing()!!, Charsets.UTF_8))
    }

    @Test
    fun `poll index resets to zero after fifteen direct sends`() {
        val e = engine(listOf("getClock", "getZoneData?zone=1"))
        e.enqueueCanIds((1..500).toList())

        // advance the poll index to 1 so the reset is observable
        assertEquals(frameOf("getClock"), String(e.onPing()!!, Charsets.UTF_8))
        e.onFrame("<request>getClock</request><clock>12:00</clock>".toByteArray(Charsets.UTF_8))
        assertEquals(1, e.currentPollIndex())
        e.onPing() // CAN branch consumes the pending canWanted

        for (i in 1..15) {
            e.enqueueDirectMessage("cmd$i")
            assertEquals(frameOf("cmd$i"), String(e.onPing()!!, Charsets.UTF_8))
            e.onFrame("<request>cmd$i</request>".toByteArray(Charsets.UTF_8))
            e.onPing() // CAN branch
        }
        assertEquals("15 sends alone must not reset the poll index", 1, e.currentPollIndex())

        e.enqueueDirectMessage("cmd16")
        assertEquals(frameOf("getClock"), String(e.onPing()!!, Charsets.UTF_8))
        assertEquals(0, e.currentPollIndex())
    }

    @Test
    fun `direct message is popped only by a matching response`() {
        val e = engine()
        e.enqueueCanIds((1..200).toList())
        e.enqueueDirectMessage("setPoint?zone=1")

        assertEquals(frameOf("setPoint?zone=1"), String(e.onPing()!!, Charsets.UTF_8))
        // unrelated response keeps the head queued
        e.onFrame("<request>getClock</request><clock>12:00</clock>".toByteArray(Charsets.UTF_8))
        e.onPing() // CAN branch
        assertEquals(frameOf("setPoint?zone=1"), String(e.onPing()!!, Charsets.UTF_8))

        e.onFrame("<request>setPoint</request><value>22</value>".toByteArray(Charsets.UTF_8))
        e.onPing() // CAN branch
        assertEquals(frameOf("getClock"), String(e.onPing()!!, Charsets.UTF_8))
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
