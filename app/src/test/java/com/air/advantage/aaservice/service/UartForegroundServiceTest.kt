package com.air.advantage.aaservice.service

import android.content.Context
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.domain.model.CanMessage
import com.air.advantage.aaservice.domain.state.UartStateMachine
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

import com.air.advantage.aaservice.domain.state.UartState

class UartForegroundServiceTest {

    private lateinit var service: UartForegroundService
    private lateinit var context: Context

    @Before
    fun setUp() {
        service = spy(UartForegroundService::class.java)
        context = mock(Context::class.java)
        
        // Stub Android framework methods to prevent NPE in unit tests
        doReturn("com.air.advantage.aaservice").`when`(service).packageName
        doReturn(null).`when`(service).registerReceiver(any(), any())
        doReturn(null).`when`(service).registerReceiver(any(), any(), anyOrNull(), anyOrNull())
        doNothing().`when`(service).unregisterReceiver(any())
        
        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    @Test
    fun `requestFullPoll iterates all 14 POLL_TAGS`() {
        UartForegroundService.instance = service
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

    @Test
    fun `enqueueUartMessage with Temperature message`() {
        service.enqueueUartMessage("Temperature")
        val msg = service.canQueue.dequeue()
        assertNotNull(msg)
        assertEquals(0, msg?.id)
        val expectedCrc = CrcCalculator.computeHex("Temperature")
        assertEquals("<U>Temperature</U=$expectedCrc>", msg?.data)
    }

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

    @Test
    fun `processCanIds with space-separated IDs`() {
        service.processCanIds("5 6 7")
        val state = service.stateMachine.getCurrentState()
        assertTrue(state is UartState.SendingCan)
        assertEquals(listOf(5, 6, 7), (state as UartState.SendingCan).messageIds)
    }

    @Test
    fun `onCreate sets instance to service`() {
        UartForegroundService.instance = null
        service.onCreate()
        assertEquals(service, UartForegroundService.instance)
    }

    @Test
    fun `onDestroy clears instance`() {
        UartForegroundService.instance = service
        service.onDestroy()
        assertNull(UartForegroundService.instance)
    }

    @Test
    fun `onBind returns null`() {
        UartForegroundService.instance = service
        assertNull(service.onBind(null))
    }
}