package com.air.advantage.aaservice.service

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.data.repository.CanStateRepository
import com.air.advantage.aaservice.data.repository.DataCacheRepository
import com.air.advantage.aaservice.data.repository.PollQueueRepository
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.domain.model.CanMessage
import com.air.advantage.aaservice.domain.state.CanMessageQueue
import com.air.advantage.aaservice.domain.state.UartState
import com.air.advantage.aaservice.domain.state.UartStateMachine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class UartForegroundServiceTest {

    private lateinit var service: UartForegroundService
    private lateinit var context: Context

    @Before
    fun setUp() {
        service = spy(UartForegroundService())
        context = mock()

        whenever(service.packageName).thenReturn("com.air.advantage.aaservice")
        whenever(service.registerReceiver(any(), any())).thenReturn(null)
        whenever(service.registerReceiver(any(), any(), anyOrNull(), anyOrNull())).thenReturn(null)
        doNothing().whenever(service).unregisterReceiver(any())

        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
    }

    // ── startUartIo ──────────────────────────────────────────────

    @Test
    fun `startUartIo creates data source and launches read job`() {
        val pfd = mock<ParcelFileDescriptor>()
        val fd = mock<java.io.FileDescriptor>()
        whenever(pfd.fileDescriptor).thenReturn(fd)

        service.startUartIo(pfd)

        assertNotNull(service.uartDataSource)
    }

    // ── handleReadStream ─────────────────────────────────────────

    @Test
    fun `handleReadStream collects all frames from data source`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            val flow = MutableSharedFlow<ByteArray>(replay = 1)
            whenever(dataSource.read()).thenReturn(flow.asSharedFlow())

            val buffer = "<U>getSystemData</U=ab>".toByteArray()
            flow.emit(buffer)

            val job = launch {
                service.handleReadStream(dataSource)
            }
            delay(50)
            job.cancel()

            verify(service).processIncomingData(eq(buffer))
        }
    }

    @Test
    fun `handleReadStream with empty flow does nothing`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            val flow = MutableSharedFlow<ByteArray>(replay = 0)
            whenever(dataSource.read()).thenReturn(flow.asSharedFlow())

            val job = launch {
                service.handleReadStream(dataSource)
            }
            delay(50)
            job.cancel()

            verify(dataSource).read()
        }
    }

    // ── processIncomingData ──────────────────────────────────────

    @Test
    fun `processIncomingData handles Ack frame`() {
        val buffer = "<ack>1</ack>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData handles Nack frame`() {
        val buffer = "<ack>0</ack>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData handles DataFrame with getSystemData`() {
        val tag = "getSystemData"
        val crc = CrcCalculator.computeHex(tag)
        val frame = "<U>$tag</U=$crc>".toByteArray()
        service.processIncomingData(frame)
        val cached = service.dataCache.get("lastFrame")
        assertNotNull(cached)
        assertArrayEquals(frame, cached)
    }

    @Test
    fun `processIncomingData handles GetCan frame`() {
        val buffer = "<U>getCAN zone1</U=00>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData handles Ping frame`() {
        val buffer = "<U>Ping</U=db>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData handles Unknown frame`() {
        val buffer = "<U><request>Unknown</request></U=00>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData with no start marker returns early`() {
        val buffer = "noStartMarkerHere".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData with CAN2 in use frame`() {
        val buffer = "<U>CAN2 in use</U=00>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    // ── handlePollCycle ──────────────────────────────────────────

    @Test
    fun `handlePollCycle sends current poll and advances`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            service.pollQueue.initialize(isMyAir5 = true)

            service.handlePollCycle(dataSource)
            delay(120)

            verify(dataSource, atLeastOnce()).write(any())
        }
    }

    @Test
    fun `handlePollCycle with empty queue does not write`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()

            service.handlePollCycle(dataSource)
            delay(120)

            verify(dataSource, never()).write(any())
        }
    }

    // ── sendCanMessages ──────────────────────────────────────────

    @Test
    fun `sendCanMessages with short frame skips write`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            service.canQueue.enqueue(CanMessage(id = 1, data = "hi"))

            service.sendCanMessages(dataSource)
            delay(50)

            verify(dataSource, never()).write(any())
        }
    }

    @Test
    fun `sendCanMessages with valid frame writes to data source and clears queue`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            (1..10).forEach { id ->
                service.canQueue.enqueue(CanMessage(id = id, data = ""))
            }

            service.sendCanMessages(dataSource)
            delay(50)

            verify(dataSource).write(any())
            assertTrue(service.canQueue.isEmpty())
        }
    }

    // ── requestFullPoll ──────────────────────────────────────────

    @Test
    fun `requestFullPoll iterates all 14 POLL_TAGS`() {
        service.requestFullPoll()
        assertEquals(14, service.canQueue.size())
        verify(service, times(14)).requestSinglePoll(any())
    }

    @Test
    fun `requestSinglePoll with getSystemData tag`() {
        service.requestSinglePoll("getSystemData")
        val msg = service.canQueue.dequeue()
        assertNotNull(msg)
        assertEquals(0, msg?.id)
        val expectedCrc = CrcCalculator.computeHex("getSystemData")
        assertEquals("<U>getSystemData</U=$expectedCrc>", msg?.data)
    }

    // ── enqueueUartMessage ───────────────────────────────────────

    @Test
    fun `enqueueUartMessage with Temperature message`() {
        service.enqueueUartMessage("Temperature")
        val msg = service.canQueue.dequeue()
        assertNotNull(msg)
        assertEquals(0, msg?.id)
        val expectedCrc = CrcCalculator.computeHex("Temperature")
        assertEquals("<U>Temperature</U=$expectedCrc>", msg?.data)
    }

    // ── enqueueCanIds ────────────────────────────────────────────

    @Test
    fun `enqueueCanIds with space-separated IDs`() {
        service.enqueueCanIds("1 2 3")
        assertEquals(3, service.canQueue.size())
        val msg1 = service.canQueue.dequeue()
        val msg2 = service.canQueue.dequeue()
        val msg3 = service.canQueue.dequeue()
        assertEquals(1, msg1?.id)
        assertEquals(2, msg2?.id)
        assertEquals(3, msg3?.id)
    }

    // ── processCanIds ────────────────────────────────────────────

    @Test
    fun `processCanIds with space-separated IDs`() {
        service.processCanIds("5 6 7")
        val state = service.stateMachine.getCurrentState()
        assertTrue(state is UartState.SendingCan)
        assertEquals(listOf(5, 6, 7), (state as UartState.SendingCan).messageIds)
    }

    // ── onCreate ─────────────────────────────────────────────────

    @Test
    fun `onCreate sets instance to service`() {
        UartForegroundService.instance = null
        service.onCreate()
        assertEquals(service, UartForegroundService.instance)
    }

    @Test
    fun `onCreate registers all 10 receivers`() {
        service.onCreate()
        assertEquals(10, service.registeredReceivers.size)
        verify(service, times(7)).registerReceiver(any(), any())
        verify(service, times(3)).registerReceiver(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `onCreate registers USB permission receiver`() {
        service.onCreate()
        assertTrue(service.registeredReceivers.any { it.javaClass.simpleName == "UsbPermissionReceiver" })
    }

    @Test
    fun `onCreate registers data receivers`() {
        service.onCreate()
        val names = service.registeredReceivers.map { it.javaClass.simpleName }
        assertTrue(names.contains("GetDataReceiver"))
        assertTrue(names.contains("GetAllDataReceiver"))
        assertTrue(names.contains("MessageToCbReceiver"))
    }

    @Test
    fun `onCreate registers secure broadcast receivers`() {
        service.onCreate()
        val names = service.registeredReceivers.map { it.javaClass.simpleName }
        assertTrue(names.contains("CanToCbReceiver"))
        assertTrue(names.contains("BroadcastCanToCbReceiver"))
        assertTrue(names.contains("BackupMessageReceiver"))
    }

    @Test
    fun `onCreate registers no-permission receivers`() {
        service.onCreate()
        val names = service.registeredReceivers.map { it.javaClass.simpleName }
        assertTrue(names.contains("CanToCbNoPermissionReceiver"))
        assertTrue(names.contains("BroadcastCanToCbNoPermissionReceiver"))
        assertTrue(names.contains("BackupMessageNoPermissionReceiver"))
    }

    // ── onDestroy ────────────────────────────────────────────────

    @Test
    fun `onDestroy clears instance`() {
        UartForegroundService.instance = service
        service.onDestroy()
        assertNull(UartForegroundService.instance)
    }

    @Test
    fun `onDestroy unregisters all receivers`() {
        service.onCreate()
        service.onDestroy()
        verify(service, times(10)).unregisterReceiver(any())
    }

    // ── onBind ───────────────────────────────────────────────────

    @Test
    fun `onBind returns null`() {
        assertNull(service.onBind(null))
    }

}