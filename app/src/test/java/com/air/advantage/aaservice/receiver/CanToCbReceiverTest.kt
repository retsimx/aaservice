package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class CanToCbReceiverTest {

    private lateinit var receiver: CanToCbReceiver
    private lateinit var context: Context
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        receiver = CanToCbReceiver()
        context = mock(Context::class.java)
        service = mock(UartForegroundService::class.java)
        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    @Test
    fun `onReceive with valid CAN IDs processes them`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.CAN_TO_CB")).thenReturn("5 6 7")
        receiver.onReceive(context, intent)
        verify(service).processCanIds("5 6 7")
    }

    @Test
    fun `onReceive with null extra returns early`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.CAN_TO_CB")).thenReturn(null)
        receiver.onReceive(context, intent)
        verifyNoInteractions(service)
    }

    @Test
    fun `onReceive with single CAN ID`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.CAN_TO_CB")).thenReturn("99")
        receiver.onReceive(context, intent)
        verify(service).processCanIds("99")
    }

    @Test
    fun `onReceive with null service does nothing`() {
        UartForegroundService.instance = null
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.CAN_TO_CB")).thenReturn("5 6 7")
        receiver.onReceive(context, intent)
    }
}