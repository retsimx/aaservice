package com.air.advantage.aaservice.data.uart

import android.hardware.usb.UsbAccessory
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.nio.charset.StandardCharsets

class MockUartDataSourceTest {

    private lateinit var mockDataSource: MockUartDataSource
    private lateinit var usbAccessory: UsbAccessory

    @Before
    fun setUp() {
        mockDataSource = MockUartDataSource()
        usbAccessory = mock(UsbAccessory::class.java)
    }

    // ── interface contract ───────────────────────────────────────

    @Test
    fun `implements UartDataSource interface`() {
        val dataSource: UartDataSource = mockDataSource
        assertNotNull(dataSource)
    }

    @Test
    fun `isConnected returns false before connect`() {
        assertFalse(mockDataSource.isConnected)
    }

    @Test
    fun `connect sets isConnected to true`() {
        val result = mockDataSource.connect(usbAccessory)
        assertTrue(result)
        assertTrue(mockDataSource.isConnected)
    }

    @Test
    fun `disconnect sets isConnected to false`() {
        mockDataSource.connect(usbAccessory)
        mockDataSource.disconnect()
        assertFalse(mockDataSource.isConnected)
    }

    @Test
    fun `write returns false when not connected`() {
        val result = runBlocking {
            mockDataSource.write("test".toByteArray())
        }
        assertFalse(result)
    }

    @Test
    fun `write returns true when connected`() {
        mockDataSource.connect(usbAccessory)
        val result = runBlocking {
            mockDataSource.write("test".toByteArray())
        }
        assertTrue(result)
    }

    @Test
    fun `read returns a Flow`() {
        val flow = mockDataSource.read()
        assertNotNull(flow)
    }

    // ── valid wire frames ────────────────────────────────────────

    @Test
    fun `ping write emits ping frame followed by ack frame`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("Ping")
        val frames = splitFrames(response)

        assertEquals(2, frames.size)
        assertEquals(MockUartDataSource.PING_FRAME, frames[0])
        assertFrame(frames[1], ACK_PAYLOAD)
    }

    @Test
    fun `ping response frames are frame-detectable`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("Ping")
        val frames = splitFrames(response)

        frames.forEach { frame ->
            assertTrue("Frame must start with <U>", frame.startsWith("<U>"))
            assertTrue("Frame must end with footer", frame.contains("</U="))
        }
    }

    @Test
    fun `getSystemData returns valid frame with expected payload`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("getSystemData")
        val frames = splitFrames(response)

        assertEquals(2, frames.size)
        assertEquals(MockUartDataSource.PING_FRAME, frames[0])
        assertFrame(frames[1], SYSTEM_DATA_PAYLOAD)
    }

    @Test
    fun `getSystemData frame footer CRC matches payload`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("getSystemData")
        val frames = splitFrames(response)

        val expectedCrc = CrcCalculator.computeHex(SYSTEM_DATA_PAYLOAD)
        assertTrue("Missing expected CRC footer", frames[1].endsWith("</U=$expectedCrc>"))
    }

    @Test
    fun `getClock returns valid frame with fixed time`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("getClock")
        val frames = splitFrames(response)

        assertEquals(2, frames.size)
        assertEquals(MockUartDataSource.PING_FRAME, frames[0])
        assertFrame(frames[1], CLOCK_PAYLOAD)
    }

    @Test
    fun `getZoneData zone 1 returns valid zone frame`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("getZoneData?zone=1")
        val frames = splitFrames(response)

        assertEquals(2, frames.size)
        assertFrame(frames[1], zonePayload(1))
    }

    @Test
    fun `getZoneData zone 5 returns zone data for zone 5`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("getZoneData?zone=5")
        val frames = splitFrames(response)

        assertFrame(frames[1], zonePayload(5))
    }

    @Test
    fun `getZoneData zone 10 returns zone data for zone 10`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("getZoneData?zone=10")
        val frames = splitFrames(response)

        assertFrame(frames[1], zonePayload(10))
    }

    @Test
    fun `getZoneData without zone defaults to zone 1`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("getZoneData")
        val frames = splitFrames(response)

        assertFrame(frames[1], zonePayload(1))
    }

    @Test
    fun `zone data frame carries a valid CRC footer`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("getZoneData?zone=3")
        val frames = splitFrames(response)
        val payload = zonePayload(3)
        val expectedCrc = CrcCalculator.computeHex(payload)

        assertEquals("<U>$payload</U=$expectedCrc>", frames[1])
    }

    @Test
    fun `unknown command returns ack frame`() {
        mockDataSource.connect(usbAccessory)

        val response = writeAndRead("someUnknownCommand")
        val frames = splitFrames(response)

        assertEquals(2, frames.size)
        assertEquals(MockUartDataSource.PING_FRAME, frames[0])
        assertFrame(frames[1], ACK_PAYLOAD)
    }

    @Test
    fun `full poll cycle simulates all commands`() {
        mockDataSource.connect(usbAccessory)

        val pollCommands = listOf(
            "Ping",
            "getSystemData",
            "getClock",
            "getZoneData?zone=1",
            "getZoneData?zone=2",
            "getZoneData?zone=3"
        )

        for (command in pollCommands) {
            val frames = splitFrames(writeAndRead(command))
            assertTrue("Response for $command should contain ping + response", frames.size == 2)
            assertEquals("Response for $command should lead with ping", MockUartDataSource.PING_FRAME, frames[0])
        }
    }

    @Test
    fun `multiple writes produce multiple responses`() {
        mockDataSource.connect(usbAccessory)

        val response1 = writeAndRead("Ping")
        val response2 = writeAndRead("getClock")

        assertTrue(String(response1, StandardCharsets.UTF_8).contains("Ping"))
        assertTrue(String(response2, StandardCharsets.UTF_8).contains("getClock"))
    }

    @Test
    fun `disconnect prevents further writes`() {
        mockDataSource.connect(usbAccessory)
        mockDataSource.disconnect()

        val result = runBlocking {
            mockDataSource.write("Ping".toByteArray())
        }
        assertFalse(result)
    }

    @Test
    fun `system data response contains original MyAppRev`() {
        mockDataSource.connect(usbAccessory)

        val frames = splitFrames(writeAndRead("getSystemData"))

        assertTrue(frames[1].contains("14.148"))
    }

    @Test
    fun `zone data response contains default temp`() {
        mockDataSource.connect(usbAccessory)

        val frames = splitFrames(writeAndRead("getZoneData?zone=1"))

        assertTrue(frames[1].contains("temp>21.0"))
    }

    @Test
    fun `zone data response contains default fan mode`() {
        mockDataSource.connect(usbAccessory)

        val frames = splitFrames(writeAndRead("getZoneData?zone=1"))

        assertTrue(frames[1].contains("fan>auto"))
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun writeAndRead(command: String): ByteArray = runBlocking {
        mockDataSource.write(command.toByteArray())
        mockDataSource.read().first()
    }

    private fun splitFrames(response: ByteArray): List<String> {
        val text = String(response, StandardCharsets.UTF_8)
        val frames = mutableListOf<String>()
        var index = 0
        while (true) {
            val start = text.indexOf("<U>", index)
            if (start < 0) break
            val endMarker = text.indexOf("</U=", start)
            if (endMarker < 0) break
            val end = text.indexOf(">", endMarker)
            if (end < 0) break
            frames.add(text.substring(start, end + 1))
            index = end + 1
        }
        return frames
    }

    private fun assertFrame(frame: String, payload: String) {
        val expectedCrc = CrcCalculator.computeHex(payload)
        assertEquals("Frame must be <U>payload</U=crc>", "<U>$payload</U=$expectedCrc>", frame)
    }

    private fun zonePayload(zone: Int): String =
        "<request>getZoneData</request><zone>$zone</zone><state>off</state><temp>21.0</temp><fan>auto</fan>"

    private companion object {
        const val ACK_PAYLOAD = "<ack>1</ack>"
        val SYSTEM_DATA_PAYLOAD = ("<request>getSystemData</request><type>00</type><AppStore>x</AppStore>" +
            "<dhcp>192.168.1.1</dhcp><subnet>255.255.255.0</subnet><gateway>192.168.1.254</gateway>" +
            "<MyAppRev>14.148</MyAppRev>")
        val CLOCK_PAYLOAD = "<request>getClock</request><time>2026-08-02 12:00:00</time>"
    }
}
