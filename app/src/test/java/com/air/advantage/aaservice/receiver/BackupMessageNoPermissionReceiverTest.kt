package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import com.air.advantage.aaservice.util.CryptoHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class BackupMessageNoPermissionReceiverTest {
    private lateinit var receiver: BackupMessageNoPermissionReceiver
    private lateinit var context: Context

    @Before
    fun setUp() {
        receiver = BackupMessageNoPermissionReceiver()
        context = mock(Context::class.java)
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    @Test
    fun `onReceive sends encrypted broadcast`() {
        val intent = Intent("com.air.advantage.BACKUP_MESSAGE_NO_PERMISSION")
        mockStatic(CryptoHelper::class.java).use { crypto ->
            val expectedEncrypted = byteArrayOf(1, 2, 3)
            crypto.`when`<ByteArray?> { CryptoHelper.encrypt(any()) }.thenReturn(expectedEncrypted)

            mockConstruction(Intent::class.java) { mockIntent, _ ->
                whenever(mockIntent.putExtra(any<String>(), any<String>())).thenReturn(mockIntent)
                whenever(mockIntent.putExtra(any<String>(), any<ByteArray>())).thenReturn(mockIntent)
            }.use { mockedIntent ->
                receiver.onReceive(context, intent)

                val constructed = mockedIntent.constructed()
                assertEquals(1, constructed.size)

                verify(context).sendBroadcast(constructed[0])
                verify(constructed[0]).putExtra("com.air.advantage.GET_DATA_REQUEST", "backupMessage")
                verify(
                    constructed[0],
                ).putExtra("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST", expectedEncrypted)
            }
        }
    }

    @Test
    fun `onReceive returns early when encrypt returns null`() {
        val intent = Intent("com.air.advantage.BACKUP_MESSAGE_NO_PERMISSION")
        mockStatic(CryptoHelper::class.java).use { crypto ->
            crypto.`when`<ByteArray?> { CryptoHelper.encrypt(any()) }.thenReturn(null)

            mockConstruction(Intent::class.java) { mockIntent, _ ->
                whenever(mockIntent.putExtra(any<String>(), any<String>())).thenReturn(mockIntent)
                whenever(mockIntent.putExtra(any<String>(), any<ByteArray>())).thenReturn(mockIntent)
            }.use { mockedIntent ->
                receiver.onReceive(context, intent)

                val constructed = mockedIntent.constructed()
                assertEquals(0, constructed.size)
                verify(context, never()).sendBroadcast(any<Intent>())
            }
        }
    }

    @Test
    fun `onReceive with null service does nothing`() {
        val intent = Intent("com.air.advantage.BACKUP_MESSAGE_NO_PERMISSION")
        UartForegroundService.instance = null
        mockStatic(CryptoHelper::class.java).use { crypto ->
            crypto.`when`<ByteArray?> { CryptoHelper.encrypt(any()) }.thenReturn(byteArrayOf(1, 2, 3))

            mockConstruction(Intent::class.java) { mockIntent, _ ->
                whenever(mockIntent.putExtra(any<String>(), any<String>())).thenReturn(mockIntent)
                whenever(mockIntent.putExtra(any<String>(), any<ByteArray>())).thenReturn(mockIntent)
            }.use {
                receiver.onReceive(context, intent)
            }
        }
    }
}
