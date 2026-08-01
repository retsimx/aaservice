package com.air.advantage.aaservice.service

import android.content.Intent
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
class BroadcastDataTest {

    private lateinit var service: UartForegroundService

    private val tag = "getClock"
    private val data = tag.toByteArray(Charsets.UTF_8)

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

    private fun seedCache() {
        service.dataCache.put(tag, data)
    }

    @Test
    fun `MESSAGE_FROM_CB extra is a ByteArray not a String`() {
        service.deviceOpen.set(true)
        seedCache()

        service.broadcastData(tag)

        val captor = argumentCaptor<Intent>()
        verify(service).sendBroadcast(captor.capture())
        val sent = captor.firstValue

        assertEquals("com.air.advantage.MESSAGE_FROM_CB", sent.action)
        assertEquals(tag, sent.getStringExtra("com.air.advantage.GET_DATA_REQUEST"))
        val extra = sent.getByteArrayExtra("com.air.advantage.MESSAGE_FROM_CB")
        assertNotNull(extra)
        assertArrayEquals(data, extra)
        assertNull(sent.getStringExtra("com.air.advantage.MESSAGE_FROM_CB"))
    }

    @Test
    fun `no MESSAGE_FROM_CB_SECURE broadcast fired`() {
        service.deviceOpen.set(true)
        seedCache()

        service.broadcastData(tag)

        verify(service, never()).sendBroadcast(argThat { action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" }, anyOrNull())
        verify(service, never()).sendBroadcast(argThat { action == "com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU" }, anyOrNull())
    }

    @Test
    fun `no broadcast when device not open`() {
        service.deviceOpen.set(false)
        seedCache()

        service.broadcastData(tag)

        verify(service, never()).sendBroadcast(any<Intent>())
    }

    @Test
    fun `broadcast sent with no permission when open`() {
        service.deviceOpen.set(true)
        seedCache()

        service.broadcastData(tag)

        val captor = argumentCaptor<Intent>()
        verify(service).sendBroadcast(captor.capture())
        assertEquals("com.air.advantage.MESSAGE_FROM_CB", captor.firstValue.action)
        verify(service, never()).sendBroadcast(any<Intent>(), anyOrNull())
    }
}
