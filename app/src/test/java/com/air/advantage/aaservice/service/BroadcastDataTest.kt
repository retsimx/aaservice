package com.air.advantage.aaservice.service

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.capture
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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

        doReturn("com.air.advantage.aaservice2").whenever(service).packageName
        whenever(service.registerReceiver(any(), any(), anyInt())).thenReturn(null)
        whenever(service.registerReceiver(any(), any(), anyOrNull(), anyOrNull(), anyInt())).thenReturn(null)
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

        verify(
            service,
            never(),
        ).sendBroadcast(argThat { action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" }, anyOrNull())
        verify(service, never()).sendBroadcast(
            argThat {
                action == "com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU"
            },
            anyOrNull(),
        )
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

    @Test
    fun `getSystemData query tag is normalized for the cache lookup`() {
        service.deviceOpen.set(true)
        val systemData = "systemData".toByteArray(Charsets.UTF_8)
        service.dataCache.put("getSystemData", systemData)

        service.broadcastData("getSystemData?something")

        val captor = argumentCaptor<Intent>()
        verify(service).sendBroadcast(captor.capture())
        val sent = captor.firstValue
        assertEquals("com.air.advantage.MESSAGE_FROM_CB", sent.action)
        // the request extra keeps the original tag; only the cache lookup is normalized
        assertEquals("getSystemData?something", sent.getStringExtra("com.air.advantage.GET_DATA_REQUEST"))
        assertArrayEquals(systemData, sent.getByteArrayExtra("com.air.advantage.MESSAGE_FROM_CB"))
    }

    @Test
    fun `getSystemData query tag with no cached data broadcasts nothing`() {
        service.deviceOpen.set(true)

        service.broadcastData("getSystemData?unknown")

        verify(service, never()).sendBroadcast(any<Intent>())
    }
}
