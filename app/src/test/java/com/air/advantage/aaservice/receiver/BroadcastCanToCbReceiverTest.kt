package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class BroadcastCanToCbReceiverTest {

    private lateinit var receiver: BroadcastCanToCbReceiver
    private lateinit var context: Context
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        receiver = BroadcastCanToCbReceiver()
        context = mock(Context::class.java)
        service = mock(UartForegroundService::class.java)
        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    @Test
    fun `onReceive with valid CAN IDs enqueues them`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.BROADCAST_CAN_TO_CB")).thenReturn("1 2 3")
        receiver.onReceive(context, intent)
        verify(service).enqueueBroadcastCanIds("1 2 3")
    }

    @Test
    fun `onReceive with null extra returns early`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.BROADCAST_CAN_TO_CB")).thenReturn(null)
        receiver.onReceive(context, intent)
        verifyNoInteractions(service)
    }

    @Test
    fun `onReceive with single CAN ID`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.BROADCAST_CAN_TO_CB")).thenReturn("42")
        receiver.onReceive(context, intent)
        verify(service).enqueueBroadcastCanIds("42")
    }

    @Test
    fun `onReceive with null service does nothing`() {
        UartForegroundService.instance = null
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.BROADCAST_CAN_TO_CB")).thenReturn("1 2 3")
        receiver.onReceive(context, intent)
    }
}