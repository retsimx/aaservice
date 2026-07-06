package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class GetAllDataReceiverTest {

    private lateinit var receiver: GetAllDataReceiver
    private lateinit var context: Context
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        receiver = GetAllDataReceiver()
        context = mock(Context::class.java)
        service = mock(UartForegroundService::class.java)
        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    @Test
    fun `onReceive calls requestFullPoll`() {
        UartForegroundService.instance = service
        val intent = Intent("com.air.advantage.GET_ALL_DATA")
        receiver.onReceive(context, intent)
        verify(service).requestFullPoll()
    }

    @Test
    fun `onReceive with null service does nothing`() {
        UartForegroundService.instance = null
        val intent = Intent("com.air.advantage.GET_ALL_DATA")
        receiver.onReceive(context, intent)
    }
}