package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class CanFrameCapTest {

    private val sink = object : UartEventSink {
        override fun onPollData(tag: String, payload: ByteArray) {}
        override fun onRawCan(payload: ByteArray) {}
    }

    private val typeBytes = "17".toByteArray(Charsets.UTF_8)
    private val appStoreBytes = "MyAir5".toByteArray(Charsets.UTF_8)

    private fun engine(): UartDispatchEngine =
        UartDispatchEngine(listOf("getClock"), typeBytes, appStoreBytes, sink)

    private fun frameOf(content: String): String =
        "<U>$content</U=${CrcCalculator.computeHex(content)}>"

    private fun firstSetCan(e: UartDispatchEngine): String =
        generateSequence { e.onPing() }
            .map { String(it, Charsets.UTF_8) }
            .first { it.startsWith("<U>setCAN ") }

    private fun idsOf(frame: String): List<Int> =
        frame.removePrefix("<U>").substringBefore("</U=").removePrefix("setCAN ").split(" ")
            .map { it.toInt() }

    @Test
    fun `setCAN caps at 25 ids and drains the remainder on re-arm`() {
        val e = engine()
        e.enqueueCanIds((1..30).toList())

        val first = firstSetCan(e)
        assertEquals(frameOf("setCAN ${(1..25).joinToString(" ")}"), first)
        assertEquals((1..25).toList(), idsOf(first))

        e.onFrame("getCAN 12345".toByteArray(Charsets.UTF_8))

        val second = firstSetCan(e)
        assertEquals(frameOf("setCAN ${(26..30).joinToString(" ")}"), second)
        assertEquals((26..30).toList(), idsOf(second))
    }
}
