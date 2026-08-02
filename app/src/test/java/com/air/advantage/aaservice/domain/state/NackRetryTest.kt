package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the NACK retry polarity of [UartDispatchEngine] against the reference `h.c(byte[])`
 * (`reference/sources/com/air/advantage/aaservice/h.java`).
 *
 * Reference behavior: a NACK while the retry counter is below 3 increments the counter and arms
 * the retry; any other frame (4th+ NACK, or a non-NACK frame) resets the counter and clears the
 * retry. Both the `<ack>0</ack>` failed-ack frame (isNack branch) and the getCAN frame whose
 * byte 7 is ASCII '0' (getCAN branch) drive the same counter.
 *
 * The retry state is observed only through onPing()/onFrame(): when retry is armed, onPing()
 * resends the stored setCAN frame byte-for-byte; when cleared, it builds a fresh one from the
 * remaining queue. Frames are seeded with `enqueueCanIds(listOf(1, 2))` so the stored frame is
 * `setCAN 1 2` and any rebuilt frame is observably different.
 */
class NackRetryTest {

    private val sink = RecordingSink()
    private val typeBytes = "17".toByteArray(Charsets.UTF_8)
    private val appStoreBytes = "MyAir5".toByteArray(Charsets.UTF_8)

    private fun engine(pollTags: List<String> = listOf("getClock")): UartDispatchEngine =
        UartDispatchEngine(pollTags, typeBytes, appStoreBytes, sink)

    private fun frameOf(content: String): String =
        "<U>$content</U=${CrcCalculator.computeHex(content)}>"

    // failed-ack NACK routed to the engine's isNack branch
    private val nackPayload = "<ack>0</ack>".toByteArray(Charsets.UTF_8)

    // 12-byte getCAN whose byte 7 != '0': a non-NACK frame per reference h.c()
    private val okPayload = "getCAN 12345".toByteArray(Charsets.UTF_8)

    /**
     * Stores a setCAN frame and returns its exact bytes: a poll ping re-arms canWanted, then the
     * CAN branch builds the setCAN from the seeded ids.
     */
    private fun storedSetCan(e: UartDispatchEngine): String {
        e.enqueueCanIds(listOf(1, 2))
        e.onPing() // poll entry re-arms canWanted
        val frame = String(e.onPing()!!, Charsets.UTF_8)
        assertTrue("expected setCAN but was $frame", frame.startsWith("<U>setCAN "))
        return frame
    }

    /**
     * Drives the ping pair that follows an inbound frame: the poll entry re-arms canWanted, then
     * the CAN branch emits either the stored frame (retry armed) or a freshly built setCAN.
     */
    private fun nextCanFrame(e: UartDispatchEngine): String {
        e.onPing() // poll entry re-arms canWanted
        return String(e.onPing()!!, Charsets.UTF_8)
    }

    @Test
    fun `first NACK arms retry and next ping resends the identical setCAN`() {
        val e = engine()
        val stored = storedSetCan(e)

        e.onFrame(nackPayload)

        assertEquals("NACK must resend the stored frame", stored, nextCanFrame(e))
        assertTrue("NACK must not broadcast", sink.rawCan.isEmpty())
    }

    @Test
    fun `second and third NACKs keep the retry armed`() {
        val e = engine()
        val stored = storedSetCan(e)

        repeat(3) {
            e.onFrame(nackPayload)
            assertEquals("NACK #${it + 1} must resend the stored frame", stored, nextCanFrame(e))
        }
    }

    @Test
    fun `fourth NACK clears retry and a new setCAN is built`() {
        val e = engine()
        val stored = storedSetCan(e)

        repeat(3) { e.onFrame(nackPayload) } // counter reaches 3, retry still armed
        e.onFrame(nackPayload) // counter already at 3 -> clear counter and retry

        val rebuilt = nextCanFrame(e)
        assertNotEquals("retry must be cleared after 3 NACKs", stored, rebuilt)
        assertTrue("a new setCAN must be built", rebuilt.startsWith("<U>setCAN "))
    }

    @Test
    fun `non-NACK getCAN clears retry broadcasts and a new setCAN is built`() {
        val e = engine()
        val stored = storedSetCan(e)

        e.onFrame(nackPayload) // arm retry
        assertEquals(stored, nextCanFrame(e))

        e.onFrame(okPayload) // byte 7 != '0' clears retry and broadcasts
        assertEquals(1, sink.rawCan.size)
        assertArrayEquals(okPayload, sink.rawCan[0])

        assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
        val rebuilt = nextCanFrame(e)
        assertNotEquals("non-NACK getCAN must clear retry", stored, rebuilt)
        assertTrue("a new setCAN must be built", rebuilt.startsWith("<U>setCAN "))
    }

    @Test
    fun `getCAN NACK retry polarity matches reference h c`() {
        val e = engine()
        e.enqueueCanIds((1..30).toList())
        e.onPing() // poll entry re-arms canWanted
        val stored = String(e.onPing()!!, Charsets.UTF_8)
        assertEquals(frameOf("setCAN ${(1..25).joinToString(" ")}"), stored)

        val getCanNack = "getCAN 0000".toByteArray(Charsets.UTF_8) // byte 7 == '0' -> NACK
        repeat(3) {
            e.onFrame(getCanNack)
            assertTrue("NACK must not broadcast", sink.rawCan.isEmpty())
            assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
            assertEquals(stored, nextCanFrame(e))
        }

        e.onFrame(getCanNack) // 4th NACK -> clear retry and broadcast
        assertEquals(1, sink.rawCan.size)
        assertArrayEquals(getCanNack, sink.rawCan[0])

        assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
        assertEquals(frameOf("setCAN 26 27 28 29 30"), nextCanFrame(e))
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
