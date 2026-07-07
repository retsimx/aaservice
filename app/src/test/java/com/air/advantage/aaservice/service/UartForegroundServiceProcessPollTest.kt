package com.air.advantage.aaservice.service

import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.domain.model.CanMessage
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UartForegroundServiceProcessPollTest {

    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        val controller = Robolectric.buildService(UartForegroundService::class.java)
        service = controller.create().get()

        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
    }

    // ── handlePollCycle tests ────────────────────────────────────

    @Test
    fun `handlePollCycle sends current poll and advances`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            whenever(dataSource.isConnected).thenReturn(true)
            whenever(dataSource.read()).thenReturn(flowOf(ByteArray(0)))

            service.pollQueue.initialize(isMyAir5 = true)
            service.handlePollCycle(dataSource)
            delay(120)

            verify(dataSource, atLeastOnce()).write(any())
        }
    }

    @Test
    fun `handlePollCycle with empty queue does not write to data source`() {
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
            service.handlePollCycle(dataSource)
            delay(120)

            verify(dataSource, atLeastOnce()).write(any())
            val state = service.stateMachine.getCurrentState()
            assertTrue("Should be in AwaitingResponse state", state is UartState.AwaitingResponse)
        }
    }

    @Test
    fun `handlePollCycle with CAN queue pending sends CAN frame`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            whenever(dataSource.isConnected).thenReturn(true)
            whenever(dataSource.read()).thenReturn(flowOf(ByteArray(0)))

            // Enqueue enough CAN IDs to make frame > 17 chars
            (1..10).forEach { id ->
                service.canQueue.enqueue(CanMessage(id = id, data = "test"))
            }

            service.handlePollCycle(dataSource)
            delay(120)

            verify(dataSource, atLeastOnce()).write(any())
            assertTrue("CAN queue should be cleared", service.canQueue.isEmpty())
        }
    }

    // ── sendCanMessages tests ────────────────────────────────────

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
    fun `sendCanMessages with valid frame writes and clears queue`() {
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

    // ── State machine tests ──────────────────────────────────────

    @Test
    fun `state machine starts in Disconnected state`() {
        val stateMachine = UartStateMachine()
        assertEquals(UartState.Disconnected, stateMachine.getCurrentState())
    }

    @Test
    fun `state machine transitions to AwaitingResponse after sendPoll`() {
        val stateMachine = UartStateMachine()
        stateMachine.onSendPoll("getSystemData", ByteArray(10))
        assertEquals(UartState.AwaitingResponse("getSystemData", 0, true), stateMachine.getCurrentState())
    }

    @Test
    fun `state machine advances after 3 retries then skip`() {
        val stateMachine = UartStateMachine()
        stateMachine.onSendPoll("getClock", ByteArray(10))

        stateMachine.onNoResponse()
        stateMachine.onNoResponse()
        stateMachine.onNoResponse()

        val state = stateMachine.getCurrentState()
        assertTrue("Should be Polling after 3 retries", state is UartState.Polling)
        assertEquals(1, (state as UartState.Polling).index)
    }

    // ── Data cache tests ─────────────────────────────────────────

    @Test
    fun `dataCache stores and retrieves data correctly`() {
        val tag = "getSystemData"
        val data = "type=17;AppStore=MyAir5".toByteArray()

        service.dataCache.put(tag, data)

        val cached = service.dataCache.get(tag)
        assertNotNull(cached)
        assertArrayEquals(data, cached)
    }

    @Test
    fun `dataCache hasChanged returns true for new tag`() {
        val tag = "getZoneData?zone=1"
        val data = "temp=22".toByteArray()

        assertTrue(service.dataCache.hasChanged(tag, data))
    }

    @Test
    fun `dataCache hasChanged returns false for unchanged data`() {
        val tag = "getTimers"
        val data = "timer1=on".toByteArray()

        service.dataCache.put(tag, data)
        assertFalse(service.dataCache.hasChanged(tag, data))
    }

    // ── PollQueueRepository tests ────────────────────────────────

    @Test
    fun `pollQueue returns correct entry after initialization`() {
        service.pollQueue.initialize(isMyAir5 = true)
        val current = service.pollQueue.currentPoll()

        assertNotNull(current)
        assertEquals("getSystemData", current?.tag)
    }

    @Test
    fun `pollQueue advanceToNext moves to next entry`() {
        service.pollQueue.initialize(isMyAir5 = true)
        service.pollQueue.advanceToNext()
        val current = service.pollQueue.currentPoll()

        assertNotNull(current)
        assertEquals("getClock", current?.tag)
    }
}
