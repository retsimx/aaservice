package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class GetDataReceiverTest {
    private lateinit var receiver: GetDataReceiver
    private lateinit var context: Context
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        receiver = GetDataReceiver()
        context = mock(Context::class.java)
        service = mock(UartForegroundService::class.java)
        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    @Test
    fun `onReceive with valid tag calls broadcastData`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.GET_DATA")).thenReturn("getSystemData")
        receiver.onReceive(context, intent)
        verify(service).broadcastData("getSystemData")
    }

    @Test
    fun `onReceive with null extra returns early`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.GET_DATA")).thenReturn(null)
        receiver.onReceive(context, intent)
        verifyNoInteractions(service)
    }

    @Test
    fun `onReceive with zone tag`() {
        UartForegroundService.instance = service
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.GET_DATA")).thenReturn("getZoneData?zone=1")
        receiver.onReceive(context, intent)
        verify(service).broadcastData("getZoneData?zone=1")
    }

    @Test
    fun `onReceive with null service does nothing`() {
        UartForegroundService.instance = null
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.GET_DATA")).thenReturn("getClock")
        receiver.onReceive(context, intent)
    }
}
