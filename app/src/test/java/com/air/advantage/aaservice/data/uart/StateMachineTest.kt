package com.air.advantage.aaservice.data.uart

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.domain.state.UartDispatchEngine
import com.air.advantage.aaservice.domain.state.UartEventSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

class StateMachineTest {

    @Test
    fun `config packet is the first write and nothing else is written yet`() {
        val input = BlockingInputStream()
        val output = ByteArrayOutputStream()
        val dataSource = UsbAccessoryDataSource()
        try {
            assertTrue(dataSource.connectWithStreams(input, output))
            assertTrue(dataSource.isConnected)
            assertArrayEquals(CONFIG_BYTES, output.toByteArray())
        } finally {
            input.finish()
            dataSource.disconnect()
        }
    }

    @Test
    fun `config write failure aborts connect`() {
        val failingOutput = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("boom")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("boom")
            }
        }
        val dataSource = UsbAccessoryDataSource()
        try {
            val result = dataSource.connectWithStreams(BlockingInputStream(), failingOutput)
            assertFalse(result)
            assertFalse(dataSource.isConnected)
        } finally {
            dataSource.disconnect()
        }
    }

    @Test
    fun `ping frame triggers exactly one onPing and writes the returned frame`() {
        val sink = RecordingSink()
        val input = BlockingInputStream()
        val output = ByteArrayOutputStream()
        val dataSource = UsbAccessoryDataSource(engine = engine(sink))
        try {
            assertTrue(dataSource.connectWithStreams(input, output))

            input.push(PING_FRAME)
            val expected = frame("getClock")
            await { output.toByteArray().size == CONFIG_BYTES.size + expected.size }

            val written = output.toByteArray()
            assertArrayEquals(CONFIG_BYTES, written.copyOfRange(0, CONFIG_BYTES.size))
            assertArrayEquals(expected, written.copyOfRange(CONFIG_BYTES.size, written.size))
            assertTrue(sink.pollData.isEmpty())
        } finally {
            input.finish()
            dataSource.disconnect()
        }
    }

    @Test
    fun `data frame does not invoke onPing and only dispatches after CRC validation`() {
        val sink = RecordingSink()
        val input = BlockingInputStream()
        val output = ByteArrayOutputStream()
        val dataSource = UsbAccessoryDataSource(engine = engine(sink))
        try {
            assertTrue(dataSource.connectWithStreams(input, output))

            input.push(frame("<request>getClock</request><clock><time>10:00</time></clock>"))
            await { sink.pollData.isNotEmpty() }

            assertTrue(sink.pollData.any { it.first == "getClock" })
            assertEquals(CONFIG_BYTES.size, output.toByteArray().size)
        } finally {
            input.finish()
            dataSource.disconnect()
        }
    }

    @Test
    fun `two pings each produce exactly one write`() {
        val sink = RecordingSink()
        val input = BlockingInputStream()
        val output = ByteArrayOutputStream()
        val dataSource = UsbAccessoryDataSource(engine = engine(sink))
        try {
            assertTrue(dataSource.connectWithStreams(input, output))

            input.push(PING_FRAME)
            input.push(PING_FRAME)
            await { remainingFrames(output.toByteArray()).size == 2 }

            assertEquals(2, remainingFrames(output.toByteArray()).size)
        } finally {
            input.finish()
            dataSource.disconnect()
        }
    }

    @Test
    fun `leading pings in a single read are stripped and compacted to the data frame`() {
        val sink = RecordingSink()
        val input = BlockingInputStream()
        val output = ByteArrayOutputStream()
        val dataSource = UsbAccessoryDataSource(engine = engine(sink))
        try {
            assertTrue(dataSource.connectWithStreams(input, output))

            val combined = PING_FRAME + PING_FRAME + frame("<request>getClock</request><state>cool</state>")
            input.push(combined)
            await { sink.pollData.isNotEmpty() }

            // One onPing per read-loop iteration (reference d()+c()); the stripped pings
            // must still be compacted away so the trailing data frame is dispatched.
            assertEquals(1, remainingFrames(output.toByteArray()).size)
            assertTrue(sink.pollData.any { it.first == "getClock" })
        } finally {
            input.finish()
            dataSource.disconnect()
        }
    }

    @Test
    fun `valid frame CRC dispatches onFrame and records crcOk true`() {
        val sink = RecordingSink()
        val input = BlockingInputStream()
        val output = ByteArrayOutputStream()
        val dataSource = UsbAccessoryDataSource(engine = engine(sink))
        try {
            assertTrue(dataSource.connectWithStreams(input, output))

            input.push(frame("getCAN 12345"))
            await { sink.rawCan.isNotEmpty() }

            input.push(PING_FRAME)
            await { String(output.toByteArray(), Charsets.UTF_8).contains("ackCAN 1") }

            assertArrayEquals("getCAN 12345".toByteArray(Charsets.UTF_8), sink.rawCan[0])
        } finally {
            input.finish()
            dataSource.disconnect()
        }
    }

    @Test
    fun `mismatched CRC records crcOk false and suppresses onFrame`() {
        val sink = RecordingSink()
        val input = BlockingInputStream()
        val output = ByteArrayOutputStream()
        val dataSource = UsbAccessoryDataSource(engine = engine(sink))
        try {
            assertTrue(dataSource.connectWithStreams(input, output))

            input.push(frame("getCAN 12345"))
            await { sink.rawCan.isNotEmpty() }

            input.push(corruptFrame(frame("getSystemData")))
            input.push(PING_FRAME)
            await { String(output.toByteArray(), Charsets.UTF_8).contains("ackCAN 0") }

            assertTrue(sink.pollData.none { it.first == "getSystemData" })
        } finally {
            input.finish()
            dataSource.disconnect()
        }
    }

    // --- helpers ---

    private fun engine(sink: RecordingSink): UartDispatchEngine =
        UartDispatchEngine(
            pollTags = listOf("getClock"),
            typeBytes = "17".toByteArray(Charsets.UTF_8),
            appStoreBytes = "MyAir5".toByteArray(Charsets.UTF_8),
            sink = sink
        )

    private fun frame(content: String): ByteArray =
        "<U>$content</U=${CrcCalculator.computeHex(content)}>".toByteArray(Charsets.UTF_8)

    private fun corruptFrame(frame: ByteArray): ByteArray {
        val out = frame.copyOf()
        val lowHex = out.size - 2
        val c = out[lowHex].toInt()
        out[lowHex] = (if (c == 'f'.code) '0'.code else c + 1).toByte()
        return out
    }

    private fun remainingFrames(output: ByteArray): List<String> {
        val body = if (output.size > CONFIG_BYTES.size) {
            output.copyOfRange(CONFIG_BYTES.size, output.size)
        } else {
            ByteArray(0)
        }
        return String(body, Charsets.UTF_8).split("<U>").drop(1).filter { it.contains("</U=") }
    }

    private fun await(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    private class BlockingInputStream : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var finished = false
        private var current: ByteArray? = null
        private var pos = 0

        fun push(data: ByteArray) {
            queue.add(data)
        }

        fun finish() {
            queue.add(POISON)
        }

        override fun read(): Int = -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val data = nextChunk() ?: return -1
            val n = minOf(data.size - pos, len)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            if (pos >= data.size) current = null
            return n
        }

        private fun nextChunk(): ByteArray? {
            current?.let { return it }
            var item = queue.poll()
            if (item == null && !finished) {
                item = queue.take()
                if (item === POISON) {
                    finished = true
                    return null
                }
            }
            if (item == null) return null
            current = item
            pos = 0
            return item
        }
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

    companion object {
        private val CONFIG_BYTES = byteArrayOf(0x00, 0xE1.toByte(), 0x00, 0x00, 0x08, 0x01, 0x00, 0x00)
        private val PING_FRAME = "<U>Ping</U=db>".toByteArray(Charsets.UTF_8)
        private val POISON = ByteArray(0)
    }
}
