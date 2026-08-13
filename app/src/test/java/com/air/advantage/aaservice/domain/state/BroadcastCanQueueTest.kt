package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the broadcast-CAN queue of [UartDispatchEngine] against the reference
 * `ServiceUart.f4131f` / `ServiceUart.i()`: `BROADCAST_CAN_TO_CB` ids live in a separate list
 * that is only consulted as a fallback when the CAN queue is empty, one item per setCAN cycle.
 */
class BroadcastCanQueueTest {
    private val sink =
        object : UartEventSink {
            override fun onPollData(
                tag: String,
                payload: ByteArray,
            ) {}

            override fun onRawCan(payload: ByteArray) {}
        }

    private val typeBytes = "17".toByteArray(Charsets.UTF_8)
    private val appStoreBytes = "MyAir5".toByteArray(Charsets.UTF_8)

    private fun engine(): UartDispatchEngine = UartDispatchEngine(listOf("getClock"), typeBytes, appStoreBytes, sink)

    private fun frameOf(content: String): String = "<U>$content</U=${CrcCalculator.computeHex(content)}>"

    private fun firstSetCan(e: UartDispatchEngine): String =
        generateSequence { e.onPing() }
            .map { String(it, Charsets.UTF_8) }
            .first { it.startsWith("<U>setCAN ") }

    @Test
    fun `broadcast CAN ids are held back while the CAN queue is non-empty`() {
        val e = engine()
        e.enqueueCanIds((1..30).map { it.toString() })
        e.enqueueBroadcastCanIds((1000..1010).map { it.toString() })

        val frame = firstSetCan(e)
        assertEquals(frameOf("setCAN ${(1..25).joinToString(" ")}"), frame)
        assertTrue("broadcast ids must not merge into the 25-cap drain", !frame.contains("1000"))
    }

    @Test
    fun `broadcast CAN id is sent as a fallback when the CAN queue is empty`() {
        val e = engine()
        e.enqueueBroadcastCanIds(listOf("1000"))

        assertEquals(frameOf("setCAN 1000"), firstSetCan(e))
    }

    @Test
    fun `CAN queue drains before the broadcast fallback is consulted`() {
        val e = engine()
        e.enqueueCanIds((1..3).map { it.toString() })
        e.enqueueBroadcastCanIds(listOf("2000"))

        assertEquals(frameOf("setCAN 1 2 3"), firstSetCan(e))

        // CAN queue empty now -> next setCAN cycle falls back to the broadcast id
        e.onFrame("getCAN 12345".toByteArray(Charsets.UTF_8))
        assertEquals(frameOf("setCAN 2000"), firstSetCan(e))
    }

    @Test
    fun `duplicate broadcast ids are enqueued once`() {
        val e = engine()
        e.enqueueBroadcastCanIds(listOf("3000", "3000", "3001"))

        assertEquals(frameOf("setCAN 3000"), firstSetCan(e))

        e.onFrame("getCAN 12345".toByteArray(Charsets.UTF_8))
        assertEquals(frameOf("setCAN 3001"), firstSetCan(e))
    }
}
