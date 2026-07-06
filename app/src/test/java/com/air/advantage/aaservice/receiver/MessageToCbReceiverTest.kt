package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class MessageToCbReceiverTest {

    private lateinit var receiver: MessageToCbReceiver
    private lateinit var context: Context
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        receiver = MessageToCbReceiver()
        context = mock(Context::class.java)
        service = mock(UartForegroundService::class.java)
        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    @Test
    fun `onReceive with non-filtered message enqueues`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB")).thenReturn("Temperature")
        receiver.onReceive(context, intent)
        verify(service).enqueueUartMessage("Temperature")
    }

    @Test
    fun `onReceive with Light message is filtered out`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB")).thenReturn("Light")
        receiver.onReceive(context, intent)
        verifyNoInteractions(service)
    }

    @Test
    fun `onReceive with Aircon message is filtered out`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB")).thenReturn("Aircon")
        receiver.onReceive(context, intent)
        verifyNoInteractions(service)
    }

    @Test
    fun `onReceive with Activation message is filtered out`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB")).thenReturn("Activation")
        receiver.onReceive(context, intent)
        verifyNoInteractions(service)
    }

    @Test
    fun `onReceive with MySystem message is filtered out`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB")).thenReturn("MySystem")
        receiver.onReceive(context, intent)
        verifyNoInteractions(service)
    }

    @Test
    fun `onReceive with null extra returns early`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB")).thenReturn(null)
        receiver.onReceive(context, intent)
        verifyNoInteractions(service)
    }

    @Test
    fun `onReceive with null service does nothing`() {
        UartForegroundService.instance = null
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB")).thenReturn("Temperature")
        receiver.onReceive(context, intent)
    }
}