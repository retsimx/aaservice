package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the NACK/retry polarity of [UartDispatchEngine] against the reference
 * (`reference/sources/com/air/advantage/aaservice/ServiceUart.java` and `h.java`).
 *
 * Reference behavior: the CAN retry counter is driven *only* by `h.c(byte[])`, which is called
 * exclusively for getCAN payloads. A regular `<ack>0</ack>` NACK never touches the retry state —
 * it is a log-only path (plus the unknown-message guard). A getCAN frame whose byte 7 is ASCII
 * '0' drives the counter: below 3 it increments and arms the retry; on the 4th it clears and
 * broadcasts. Any non-NACK getCAN resets the counter and clears the retry.
 *
 * A plain NACK also leaves `canMessageArmed` untouched, so the stored setCAN frame keeps being
 * resent on subsequent CAN slots until a getCAN response arrives and clears it.
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

    // failed-ack NACK routed to the engine's isNack branch (log-only in the reference)
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
     * the CAN branch emits either the stored frame (retry/armed) or a freshly built setCAN.
     */
    private fun nextCanFrame(e: UartDispatchEngine): String {
        e.onPing() // poll entry re-arms canWanted
        return String(e.onPing()!!, Charsets.UTF_8)
    }

    @Test
    fun `plain NACK does not touch CAN retry but the stored setCAN is resent`() {
        val e = engine()
        val stored = storedSetCan(e)

        e.onFrame(nackPayload)

        assertEquals("stored frame must be resent", stored, nextCanFrame(e))
        assertTrue("NACK must not broadcast", sink.rawCan.isEmpty())
    }

    @Test
    fun `repeated plain NACKs keep resending the stored setCAN`() {
        val e = engine()
        val stored = storedSetCan(e)

        repeat(3) {
            e.onFrame(nackPayload)
            assertEquals("NACK #${it + 1} must resend the stored frame", stored, nextCanFrame(e))
        }
    }

    @Test
    fun `plain NACKs never drive the CAN retry counter`() {
        val e = engine()
        val stored = storedSetCan(e)

        // Four NACKs: the reference retry counter (h.f4190e) is only advanced by h.c() for
        // getCAN payloads, so the stored frame keeps being resent.
        repeat(4) {
            e.onFrame(nackPayload)
            assertEquals("plain NACK must resend the stored frame", stored, nextCanFrame(e))
        }

        // A getCAN NACK (byte 7 == '0') still drives the getCAN retry path independently.
        e.onFrame("getCAN 0000".toByteArray(Charsets.UTF_8))
        assertTrue("getCAN NACK must not broadcast", sink.rawCan.isEmpty())
        assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
        assertEquals("getCAN NACK must resend the stored frame", stored, nextCanFrame(e))
    }

    @Test
    fun `non-NACK getCAN clears armed state broadcasts and a new setCAN is built`() {
        val e = engine()
        val stored = storedSetCan(e)

        e.onFrame(nackPayload) // keeps the stored frame armed
        assertEquals(stored, nextCanFrame(e))

        e.onFrame(okPayload) // byte 7 != '0' clears retry/armed and broadcasts
        assertEquals(1, sink.rawCan.size)
        assertArrayEquals(okPayload, sink.rawCan[0])

        assertEquals(frameOf("ackCAN 1"), String(e.onPing()!!, Charsets.UTF_8))
        val rebuilt = nextCanFrame(e)
        assertNotEquals("non-NACK getCAN must clear the armed state", stored, rebuilt)
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
