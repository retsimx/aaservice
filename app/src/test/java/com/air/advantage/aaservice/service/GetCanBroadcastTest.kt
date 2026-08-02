package com.air.advantage.aaservice.service

import android.content.Intent
import com.air.advantage.aaservice.util.CryptoHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class GetCanBroadcastTest {

    private lateinit var service: UartForegroundService

    private val getCanPayload = "getCAN zone1"

    @Before
    fun setUp() {
        val controller = Robolectric.buildService(UartForegroundService::class.java)
        service = spy(controller.get())

        doReturn("com.air.advantage.aaservice2").whenever(service).packageName
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

    @Test
    fun `getCAN payload sends MESSAGE_FROM_CB_SECURE with rawCan and payload extra`() {
        service.handleGetCan(getCanPayload)

        val secureCaptor = ArgumentCaptor.forClass(Intent::class.java)
        verify(service).sendBroadcast(secureCaptor.capture(), eq("com.air.android.secure_comms"))

        val secureIntent = secureCaptor.value
        assertEquals("com.air.advantage.MESSAGE_FROM_CB_SECURE", secureIntent.action)
        assertEquals("rawCan", secureIntent.getStringExtra("com.air.advantage.GET_DATA_REQUEST"))
        assertEquals(getCanPayload, secureIntent.getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE"))
    }

    @Test
    fun `getCAN payload sends encrypted no-permission broadcast to explicit component`() {
        service.handleGetCan(getCanPayload)

        val noPermCaptor = ArgumentCaptor.forClass(Intent::class.java)
        verify(service).sendBroadcast(noPermCaptor.capture())

        val noPermIntent = noPermCaptor.value
        assertEquals("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST", noPermIntent.action)
        assertEquals("com.air.advantage.zone10", noPermIntent.component?.packageName)
        assertEquals(
            "com.air.advantage.ReceiverDataUartForNoPermissionBroadcast",
            noPermIntent.component?.className
        )
        assertEquals("rawCan", noPermIntent.getStringExtra("com.air.advantage.GET_DATA_REQUEST"))
        val encrypted = noPermIntent.getByteArrayExtra("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST")
        assertNotNull(encrypted)
        assertTrue("Encrypted bytes should be non-empty", encrypted!!.isNotEmpty())
        assertArrayEquals(
            getCanPayload.toByteArray(Charsets.UTF_8),
            CryptoHelper.decrypt(encrypted)
        )
    }

    @Test
    fun `fujitsu variant uses fujitsu secure action and permission`() {
        doReturn("com.air.advantage.fgassist").whenever(service).packageName

        service.handleGetCan(getCanPayload)

        val secureCaptor = ArgumentCaptor.forClass(Intent::class.java)
        verify(service).sendBroadcast(secureCaptor.capture(), eq("com.air.android.secure_comms_fujitsu"))

        val secureIntent = secureCaptor.value
        assertEquals("com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU", secureIntent.action)
        assertEquals(getCanPayload, secureIntent.getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU"))
    }
}
