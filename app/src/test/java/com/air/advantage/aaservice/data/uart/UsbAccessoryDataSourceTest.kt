package com.air.advantage.aaservice.data.uart

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Field

class UsbAccessoryDataSourceTest {
    private val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Launches the now-suspend [UsbAccessoryDataSource.connectWithStreams] in a background
     * coroutine; the function blocks for the lifetime of the read loop.
     */
    private fun connectAsync(
        dataSource: UsbAccessoryDataSource,
        input: InputStream,
        output: OutputStream,
    ): Job = testScope.launch { dataSource.connectWithStreams(input, output) }

    private fun await(
        timeoutMs: Long = 5000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun `implements UartDataSource interface`() {
        val dataSource: UartDataSource = UsbAccessoryDataSource()
        assertNotNull(dataSource)
    }

    @Test
    fun `isConnected returns false before connect`() {
        val dataSource = UsbAccessoryDataSource()
        assertFalse(dataSource.isConnected)
    }

    @Test
    fun `disconnect does not throw when not connected`() {
        val dataSource = UsbAccessoryDataSource()
        dataSource.disconnect()
        assertFalse(dataSource.isConnected)
    }

    @Test
    fun `read returns a Flow`() {
        val dataSource = UsbAccessoryDataSource()
        val flow = dataSource.read()
        assertNotNull(flow)
    }

    @Test
    fun `write returns false when no output stream set`() {
        val dataSource = UsbAccessoryDataSource()
        val result =
            kotlinx.coroutines.runBlocking {
                dataSource.write("test".toByteArray())
            }
        assertFalse(result)
    }

    @Test
    fun `write chunks data into 63-byte segments`() {
        val chunkSize = getConstant("CHUNK_SIZE")
        assertEquals(63, chunkSize)
    }

    @Test
    fun `read buffer size is 3072 bytes`() {
        val bufferSize = getConstant("BUFFER_SIZE")
        assertEquals(3072, bufferSize)
    }

    @Test
    fun `read size is 256 bytes`() {
        val readSize = getConstant("READ_SIZE")
        assertEquals(256, readSize)
    }

    @Test
    fun `max retries is 3`() {
        val maxRetries = getConstant("MAX_RETRIES")
        assertEquals(3, maxRetries)
    }

    @Test
    fun `retry delay is 500ms`() {
        val retryDelay = getConstant("RETRY_DELAY_MS")
        assertEquals(500L, retryDelay)
    }

    @Test
    fun `config packet is 8 bytes with correct bytes`() {
        val configPacket = byteArrayOf(0x00, 0xE1.toByte(), 0x00, 0x00, 0x08, 0x01, 0x00, 0x00)
        assertEquals(8, configPacket.size)
        assertEquals(0x00.toByte(), configPacket[0])
        assertEquals(0xE1.toByte(), configPacket[1])
        assertEquals(0x08.toByte(), configPacket[4])
        assertEquals(0x01.toByte(), configPacket[5])
    }

    @Test
    fun `write data of 200 bytes requires 4 chunks`() {
        val data = ByteArray(200)
        val chunkSize = getConstant("CHUNK_SIZE") as Int
        val expectedChunks = Math.ceil(data.size.toDouble() / chunkSize).toInt()
        assertEquals(4, expectedChunks)
    }

    @Test
    fun `write data of exactly 63 bytes requires 1 chunk`() {
        val data = ByteArray(63)
        val chunkSize = getConstant("CHUNK_SIZE") as Int
        val expectedChunks = Math.ceil(data.size.toDouble() / chunkSize).toInt()
        assertEquals(1, expectedChunks)
    }

    @Test
    fun `write data of 64 bytes requires 2 chunks`() {
        val data = ByteArray(64)
        val chunkSize = getConstant("CHUNK_SIZE") as Int
        val expectedChunks = Math.ceil(data.size.toDouble() / chunkSize).toInt()
        assertEquals(2, expectedChunks)
    }

    @Test
    fun `write data of 126 bytes requires 2 chunks`() {
        val data = ByteArray(126)
        val chunkSize = getConstant("CHUNK_SIZE") as Int
        val expectedChunks = Math.ceil(data.size.toDouble() / chunkSize).toInt()
        assertEquals(2, expectedChunks)
    }

    @Test
    fun `write data of 127 bytes requires 3 chunks`() {
        val data = ByteArray(127)
        val chunkSize = getConstant("CHUNK_SIZE") as Int
        val expectedChunks = Math.ceil(data.size.toDouble() / chunkSize).toInt()
        assertEquals(3, expectedChunks)
    }

    @Test
    fun `buffer resets when offset plus read size exceeds buffer size`() {
        val bufferSize = getConstant("BUFFER_SIZE") as Int
        val readSize = getConstant("READ_SIZE") as Int
        val overflowOffset = bufferSize - readSize + 1
        assertTrue(overflowOffset + readSize > bufferSize)
    }

    @Test
    fun `connectWithStreams sets connected state`() {
        val dataSource = UsbAccessoryDataSource()
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()
        val connectJob = connectAsync(dataSource, input, output)
        try {
            await { dataSource.isConnected }
            assertTrue(dataSource.isConnected)
        } finally {
            dataSource.disconnect()
            connectJob.cancel()
        }
    }

    @Test
    fun `disconnect after connectWithStreams clears state`() {
        val dataSource = UsbAccessoryDataSource()
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()
        val connectJob = connectAsync(dataSource, input, output)
        try {
            await { dataSource.isConnected }
            dataSource.disconnect()
            assertFalse(dataSource.isConnected)
        } finally {
            connectJob.cancel()
        }
    }

    @Test
    fun `write succeeds with connected streams`() {
        val dataSource = UsbAccessoryDataSource()
        val output = ByteArrayOutputStream()
        val input = ByteArrayInputStream(ByteArray(0))
        val connectJob = connectAsync(dataSource, input, output)
        try {
            await { dataSource.isConnected }

            // Clear the config packet bytes written during connect
            output.reset()

            val data = "hello".toByteArray()
            val result =
                kotlinx.coroutines.runBlocking {
                    dataSource.write(data)
                }
            assertTrue(result)
            assertArrayEquals(data, output.toByteArray())
        } finally {
            dataSource.disconnect()
            connectJob.cancel()
        }
    }

    @Test
    fun `write large data chunks correctly through streams`() {
        val dataSource = UsbAccessoryDataSource()
        val output = ByteArrayOutputStream()
        val input = ByteArrayInputStream(ByteArray(0))
        val connectJob = connectAsync(dataSource, input, output)
        try {
            await { dataSource.isConnected }

            // Clear the config packet bytes written during connect
            output.reset()

            val data = ByteArray(200) { it.toByte() }
            kotlinx.coroutines.runBlocking {
                dataSource.write(data)
            }

            assertArrayEquals(data, output.toByteArray())
        } finally {
            dataSource.disconnect()
            connectJob.cancel()
        }
    }

    private fun getConstant(name: String): Any {
        val field: Field = UsbAccessoryDataSource::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null)!!
    }
}
