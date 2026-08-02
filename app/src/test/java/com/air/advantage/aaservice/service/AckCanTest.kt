package com.air.advantage.aaservice.service

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.domain.model.CanMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the ackCAN priority-1 send branch in [UartForegroundService.handlePollCycle].
 *
 * The poll cycle is an infinite loop on an IO scope, so these tests delay after starting it
 * and (where determinism matters) capture all writes, asserting the ackCAN frame comes first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AckCanTest {

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

    @Test
    fun `ackCAN 0 is sent with correct frame when armed after CRC fail`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            service.ackCanArmed = true
            service.lastCrcResult = 0

            service.handlePollCycle(dataSource)
            delay(120)

            val payload = "ackCAN 0"
            val expected = "<U>$payload</U=${CrcCalculator.computeHex(payload)}>".toByteArray(Charsets.UTF_8)
            verify(dataSource).write(eq(expected))
            assertFalse(service.ackCanArmed)
            assertTrue(service.messageSent.get())
        }
    }

    @Test
    fun `ackCAN 1 is sent with correct frame when armed after CRC pass`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            service.ackCanArmed = true
            service.lastCrcResult = 1

            service.handlePollCycle(dataSource)
            delay(120)

            val payload = "ackCAN 1"
            val expected = "<U>$payload</U=${CrcCalculator.computeHex(payload)}>".toByteArray(Charsets.UTF_8)
            verify(dataSource).write(eq(expected))
            assertFalse(service.ackCanArmed)
            assertTrue(service.messageSent.get())
        }
    }

    @Test
    fun `ackCAN is sent before queued CAN messages`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            (1..10).forEach { id ->
                service.canQueue.enqueue(CanMessage(id = id, data = ""))
            }
            service.ackCanArmed = true

            service.handlePollCycle(dataSource)
            delay(120)
            service.onDestroy()

            val captor = argumentCaptor<ByteArray>()
            verify(dataSource, atLeastOnce()).write(captor.capture())
            val allWrites = captor.allValues
            assertTrue("Expected a write, got none", allWrites.isNotEmpty())
            assertTrue(
                "First write should be the ackCAN frame, got: ${String(allWrites[0], Charsets.UTF_8)}",
                String(allWrites[0], Charsets.UTF_8).contains("ackCAN")
            )
        }
    }

    @Test
    fun `no ackCAN write when not armed`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            service.ackCanArmed = false

            service.handlePollCycle(dataSource)
            delay(120)

            verify(dataSource, never()).write(argThat<ByteArray> {
                String(this, Charsets.UTF_8).contains("ackCAN")
            })
        }
    }
}
