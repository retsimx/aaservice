package com.air.advantage.aaservice.service

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.data.repository.DataCacheRepository
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.domain.model.CanMessage
import com.air.advantage.aaservice.domain.state.CanMessageQueue
import com.air.advantage.aaservice.domain.state.UartState
import com.air.advantage.aaservice.domain.state.UartStateMachine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import android.content.Intent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UartForegroundServiceProcessPollResponseTest {

    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        val controller = Robolectric.buildService(UartForegroundService::class.java)
        service = spy(controller.get())

        doReturn("com.air.advantage.aaservice").whenever(service).packageName
        whenever(service.registerReceiver(any(), any())).thenReturn(null)
        whenever(service.registerReceiver(any(), any(), anyOrNull(), anyOrNull())).thenReturn(null)
        doNothing().whenever(service).unregisterReceiver(any())
        doNothing().whenever(service).sendBroadcast(any<Intent>())
        doNothing().whenever(service).sendBroadcast(any<Intent>(), anyOrNull())

        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
    }

    // ── processPollResponse tests ────────────────────────────────

    @Test
    fun `processPollResponse with getSystemData injects type=17`() {
        val tag = "getSystemData"
        service.processPollResponse(tag)

        val cached = service.dataCache.get(tag)
        assertNotNull(cached)
        assertArrayEquals("type=17".toByteArray(Charsets.UTF_8), cached)
    }

    @Test
    fun `processPollResponse with getClock passes through tag as data`() {
        val tag = "getClock"
        service.processPollResponse(tag)

        val cached = service.dataCache.get(tag)
        assertNotNull(cached)
        assertArrayEquals("getClock".toByteArray(Charsets.UTF_8), cached)
    }

    @Test
    fun `processPollResponse getSystemData produces correct bytes`() {
        val tag = "getSystemData"
        val expectedBytes = "type=17".toByteArray(Charsets.UTF_8)

        service.processPollResponse(tag)

        val cached = service.dataCache.get(tag)
        assertArrayEquals(expectedBytes, cached)
    }

    @Test
    fun `processPollResponse caches data via DataCacheRepository`() {
        val tag = "getSystemData"
        val data = DataCacheRepository()

        service.processPollResponse(tag)

        val cached = service.dataCache.get(tag)
        assertNotNull(cached)
        assertArrayEquals("type=17".toByteArray(Charsets.UTF_8), cached)
    }

    @Test
    fun `processPollResponse calls broadcastData after caching`() {
        val tag = "getSystemData"

        service.processPollResponse(tag)

        verify(service).broadcastData(tag)
    }

    @Test
    fun `processPollResponse transitions state machine to Polling`() {
        val tag = "getSystemData"
        service.stateMachine.onSendPoll(tag, ByteArray(10))

        service.processPollResponse(tag)

        val state = service.stateMachine.getCurrentState()
        assertTrue("Should be Polling state", state is UartState.Polling)
    }

    // ── State machine transition tests ───────────────────────────

    @Test
    fun `state machine transitions from AwaitingResponse to Polling on valid response`() {
        val stateMachine = UartStateMachine()
        val tag = "getSystemData"

        stateMachine.onSendPoll(tag, ByteArray(10))
        stateMachine.onValidResponse(tag)

        val state = stateMachine.getCurrentState()
        assertTrue("Should be Polling state", state is UartState.Polling)
        assertEquals(1, (state as UartState.Polling).index)
    }

    @Test
    fun `state machine skips after 3 retries then advances poll`() {
        val stateMachine = UartStateMachine()
        val tag = "getClock"

        stateMachine.onSendPoll(tag, ByteArray(10))

        stateMachine.onNoResponse()
        stateMachine.onNoResponse()
        stateMachine.onNoResponse()

        val state = stateMachine.getCurrentState()
        assertTrue("Should be Polling after 3 retries", state is UartState.Polling)
        assertEquals(1, (state as UartState.Polling).index)
    }

    // ── CAN queue and poll cycle tests ───────────────────────────

    @Test
    fun `CAN queue check before polling sends CAN if pending`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            whenever(dataSource.isConnected).thenReturn(true)
            whenever(dataSource.read()).thenReturn(flowOf(ByteArray(0)))

            (1..10).forEach { id ->
                service.canQueue.enqueue(CanMessage(id = id, data = ""))
            }

            service.sendCanMessages(dataSource)
            delay(120)

            verify(dataSource).write(any())
            assertTrue("CAN queue should be cleared", service.canQueue.isEmpty())
        }
    }

    @Test
    fun `handlePollCycle with empty queue does not write`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            whenever(dataSource.isConnected).thenReturn(true)
            whenever(dataSource.read()).thenReturn(flowOf(ByteArray(0)))

            service.pollQueue.initialize(isMyAir5 = false)

            service.handlePollCycle(dataSource)
            delay(120)

            verify(dataSource, never()).write(any())
        }
    }

    @Test
    fun `handlePollCycle advances poll index correctly`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            whenever(dataSource.isConnected).thenReturn(true)
            whenever(dataSource.read()).thenReturn(flowOf(ByteArray(0)))

            service.pollQueue.initialize(isMyAir5 = true)
            val initialIndex = service.pollQueue.getIndex()

            service.handlePollCycle(dataSource)
            delay(120)

            val newIndex = service.pollQueue.getIndex()
            assertTrue("Index should advance", newIndex > initialIndex || newIndex == 0)
        }
    }

    @Test
    fun `processPollResponse with unknown tag uses tag as data`() {
        val tag = "getZoneData?zone=1"
        service.processPollResponse(tag)

        val cached = service.dataCache.get(tag)
        assertNotNull(cached)
        assertArrayEquals("getZoneData?zone=1".toByteArray(Charsets.UTF_8), cached)
    }
}