package com.air.advantage.aaservice.service

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.spy
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the CRC validation + executor dispatch path in [UartForegroundService.processIncomingData].
 *
 * The parse executor is a real single-thread executor, so these tests assert only synchronous
 * state (lastCrcResult, ackCanArmed) plus the absence of the removed `lastFrame` cache entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReadLoopCrcTest {

    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        val controller = Robolectric.buildService(UartForegroundService::class.java)
        service = spy(controller.get())
        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
    }

    private fun frameFor(tag: String): ByteArray {
        val crc = CrcCalculator.computeHex(tag)
        return "<U>$tag</U=$crc>".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `CRC-pass frame sets lastCrcResult to 1`() {
        service.lastCrcResult = 0
        service.processIncomingData(frameFor("getClock"))

        assertEquals(1, service.lastCrcResult)
    }

    @Test
    fun `CRC-fail frame sets lastCrcResult to 0 and is not dispatched`() {
        service.lastCrcResult = 1

        val corrupt = frameFor("getClock")
        corrupt[corrupt.size - 3] = '0'.code.toByte()
        corrupt[corrupt.size - 2] = '0'.code.toByte()
        service.processIncomingData(corrupt)

        assertEquals(0, service.lastCrcResult)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `getCAN frame with valid CRC arms ackCan`() {
        service.ackCanArmed = false
        service.processIncomingData(frameFor("getCAN zone1"))

        assertTrue(service.ackCanArmed)
    }

    @Test
    fun `Ping frame bypasses CRC and does not change state`() {
        service.lastCrcResult = 3
        service.ackCanArmed = false

        service.processIncomingData("<U>Ping</U=db>".toByteArray(Charsets.UTF_8))

        assertEquals(3, service.lastCrcResult)
        assertFalse(service.ackCanArmed)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `buffer without start marker returns early`() {
        service.lastCrcResult = 3
        service.ackCanArmed = true

        service.processIncomingData("noStartMarkerHere".toByteArray(Charsets.UTF_8))

        assertEquals(3, service.lastCrcResult)
        assertTrue(service.ackCanArmed)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `valid getSystemData frame sets lastCrcResult to 1`() {
        service.lastCrcResult = 0
        service.processIncomingData(frameFor("getSystemData"))

        assertEquals(1, service.lastCrcResult)
    }
}
