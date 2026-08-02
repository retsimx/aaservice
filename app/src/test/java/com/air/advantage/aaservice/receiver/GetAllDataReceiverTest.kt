package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

class GetAllDataReceiverTest {

    private lateinit var receiver: GetAllDataReceiver
    private lateinit var context: Context
    private lateinit var service: UartForegroundService

    private val baseTags = listOf(
        "getSystemData", "getClock",
        "getZoneData?zone=1", "getZoneData?zone=2", "getZoneData?zone=3",
        "getZoneData?zone=4", "getZoneData?zone=5", "getZoneData?zone=6",
        "getZoneData?zone=7", "getZoneData?zone=8", "getZoneData?zone=9",
        "getZoneData?zone=10"
    )

    private val scheduleTags = listOf(
        "getZoneTimer",
        "getScheduleData?schedule=1",
        "getScheduleData?schedule=2",
        "getScheduleData?schedule=3",
        "getScheduleData?schedule=4",
        "getScheduleData?schedule=5"
    )

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
    fun `onReceive with device closed broadcasts nothing and polls nothing`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(false))

        receiver.onReceive(context, Intent("com.air.advantage.GET_ALL_DATA"))

        verify(service, never()).broadcastData(anyString())
        verify(service, never()).requestSinglePoll(anyString())
    }

    @Test
    fun `onReceive with device open broadcasts all base and schedule tags`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, Intent("com.air.advantage.GET_ALL_DATA"))

        baseTags.forEach { verify(service).broadcastData(it) }
        scheduleTags.forEach { verify(service).broadcastData(it) }
        verify(service, times(baseTags.size + scheduleTags.size)).broadcastData(anyString())
    }

    @Test
    fun `onReceive with device open enqueues every schedule tag unconditionally`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, Intent("com.air.advantage.GET_ALL_DATA"))

        scheduleTags.forEach { verify(service).requestSinglePoll(it) }
        verify(service, times(scheduleTags.size)).requestSinglePoll(anyString())
    }

    @Test
    fun `onReceive with null service does nothing`() {
        UartForegroundService.instance = null

        receiver.onReceive(context, Intent("com.air.advantage.GET_ALL_DATA"))

        verifyNoInteractions(service)
    }
}
