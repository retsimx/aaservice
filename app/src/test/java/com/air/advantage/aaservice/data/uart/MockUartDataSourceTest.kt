package com.air.advantage.aaservice.data.uart

import android.hardware.usb.UsbAccessory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun `ping command returns ping response and ack`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("Ping".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("<U>Ping</U=db>"))
        assertTrue(responseStr.contains("<ack>1</ack>"))
    }

    @Test
    fun `getSystemData returns system data frame`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("getSystemData".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("type=17"))
        assertTrue(responseStr.contains("AppStore=MyAir5"))
        assertTrue(responseStr.contains("<ack>1</ack>"))
    }

    @Test
    fun `getClock returns clock response`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("getClock".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("time="))
        assertTrue(responseStr.contains("<ack>1</ack>"))
    }

    @Test
    fun `getZoneData zone 1 returns zone data`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("getZoneData?zone=1".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("zone=1"))
        assertTrue(responseStr.contains("state=off"))
        assertTrue(responseStr.contains("<ack>1</ack>"))
    }

    @Test
    fun `getZoneData zone 5 returns zone data for zone 5`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("getZoneData?zone=5".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("zone=5"))
        assertTrue(responseStr.contains("state=off"))
    }

    @Test
    fun `unknown command returns ack`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("someUnknownCommand".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("<ack>1</ack>"))
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
            val response = runBlocking {
                mockDataSource.write(command.toByteArray())
                mockDataSource.read().first()
            }
            assertNotNull(response)
            assertTrue("Response for $command should not be empty", response.isNotEmpty())
        }
    }

    @Test
    fun `multiple writes produce multiple responses`() {
        mockDataSource.connect(usbAccessory)

        runBlocking {
            mockDataSource.write("Ping".toByteArray())
            val response1 = mockDataSource.read().first()
            assertTrue(String(response1, StandardCharsets.UTF_8).contains("Ping"))

            mockDataSource.write("getClock".toByteArray())
            val response2 = mockDataSource.read().first()
            assertTrue(String(response2, StandardCharsets.UTF_8).contains("time="))
        }
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
    fun `getZoneData zone 10 returns zone data for zone 10`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("getZoneData?zone=10".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("zone=10"))
        assertTrue(responseStr.contains("state=off"))
    }

    @Test
    fun `system data response contains correct model`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("getSystemData".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("Model=MyAir5"))
    }

    @Test
    fun `zone data response contains default temp`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("getZoneData?zone=1".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("temp=21.0"))
    }

    @Test
    fun `zone data response contains default fan mode`() {
        mockDataSource.connect(usbAccessory)

        val response = runBlocking {
            mockDataSource.write("getZoneData?zone=1".toByteArray())
            mockDataSource.read().first()
        }

        val responseStr = String(response, StandardCharsets.UTF_8)
        assertTrue(responseStr.contains("fan=auto"))
    }
}
