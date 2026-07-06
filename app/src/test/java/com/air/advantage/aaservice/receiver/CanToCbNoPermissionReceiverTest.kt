package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import com.air.advantage.aaservice.util.CryptoHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class CanToCbNoPermissionReceiverTest {

    private lateinit var receiver: CanToCbNoPermissionReceiver
    private lateinit var context: Context
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        receiver = CanToCbNoPermissionReceiver()
        context = mock(Context::class.java)
        service = mock(UartForegroundService::class.java)
        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    @Test
    fun `onReceive decrypts and processes CAN IDs`() {
        mockStatic(CryptoHelper::class.java).use { crypto ->
            val encrypted = byteArrayOf(1, 2, 3)
            val decrypted = "11 22 33".toByteArray()
            crypto.`when`<ByteArray?> { CryptoHelper.decrypt(any()) }.thenReturn(decrypted)

            val intent = mock(Intent::class.java)
            whenever(intent.getByteArrayExtra("com.air.advantage.CAN_TO_CB_NO_PERMISSION")).thenReturn(encrypted)

            receiver.onReceive(context, intent)
            verify(service).processCanIds("11 22 33")
        }
    }

    @Test
    fun `onReceive returns early when extra is null`() {
        mockStatic(CryptoHelper::class.java).use {
            val intent = mock(Intent::class.java)
            whenever(intent.getByteArrayExtra("com.air.advantage.CAN_TO_CB_NO_PERMISSION")).thenReturn(null)

            receiver.onReceive(context, intent)
            verifyNoInteractions(service)
        }
    }

    @Test
    fun `onReceive returns early when decrypt returns null`() {
        mockStatic(CryptoHelper::class.java).use { crypto ->
            val encrypted = byteArrayOf(1, 2, 3)
            crypto.`when`<ByteArray?> { CryptoHelper.decrypt(any()) }.thenReturn(null)

            val intent = mock(Intent::class.java)
            whenever(intent.getByteArrayExtra("com.air.advantage.CAN_TO_CB_NO_PERMISSION")).thenReturn(encrypted)

            receiver.onReceive(context, intent)
            verifyNoInteractions(service)
        }
    }

    @Test
    fun `onReceive with null service does nothing`() {
        UartForegroundService.instance = null
        mockStatic(CryptoHelper::class.java).use { crypto ->
            val encrypted = byteArrayOf(1, 2, 3)
            val decrypted = "11 22".toByteArray()
            crypto.`when`<ByteArray?> { CryptoHelper.decrypt(any()) }.thenReturn(decrypted)

            val intent = mock(Intent::class.java)
            whenever(intent.getByteArrayExtra("com.air.advantage.CAN_TO_CB_NO_PERMISSION")).thenReturn(encrypted)

            receiver.onReceive(context, intent)
        }
    }
}