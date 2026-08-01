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

    private val getCanFrame = "<U>getCAN zone1</U=00>"

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

    @Test
    fun `getCAN frame sends MESSAGE_FROM_CB_SECURE with rawCan and frame extra`() {
        service.processIncomingData(getCanFrame.toByteArray(Charsets.UTF_8))

        val secureCaptor = ArgumentCaptor.forClass(Intent::class.java)
        verify(service).sendBroadcast(secureCaptor.capture(), eq("com.air.android.secure_comms"))

        val secureIntent = secureCaptor.value
        assertEquals("com.air.advantage.MESSAGE_FROM_CB_SECURE", secureIntent.action)
        assertEquals("rawCan", secureIntent.getStringExtra("com.air.advantage.GET_DATA_REQUEST"))
        assertEquals(getCanFrame, secureIntent.getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE"))
    }

    @Test
    fun `getCAN frame sends encrypted no-permission broadcast to explicit component`() {
        service.processIncomingData(getCanFrame.toByteArray(Charsets.UTF_8))

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
            getCanFrame.toByteArray(Charsets.UTF_8),
            CryptoHelper.decrypt(encrypted)
        )
    }

    @Test
    fun `fujitsu variant uses fujitsu secure action and permission`() {
        doReturn("com.air.advantage.fgassist").whenever(service).packageName

        service.processIncomingData(getCanFrame.toByteArray(Charsets.UTF_8))

        val secureCaptor = ArgumentCaptor.forClass(Intent::class.java)
        verify(service).sendBroadcast(secureCaptor.capture(), eq("com.air.android.secure_comms_fujitsu"))

        val secureIntent = secureCaptor.value
        assertEquals("com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU", secureIntent.action)
        assertEquals(getCanFrame, secureIntent.getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU"))
    }

    @Test
    fun `non-getCAN frame sends no secure or no-permission broadcast`() {
        val buffer = "<U>Ping</U=db>".toByteArray(Charsets.UTF_8)
        service.processIncomingData(buffer)

        verify(service, never()).sendBroadcast(any<Intent>())
        verify(service, never()).sendBroadcast(any<Intent>(), anyOrNull())
    }
}
