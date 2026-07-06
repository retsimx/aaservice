package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import com.air.advantage.aaservice.util.FujitsuDetector
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class BackupMessageReceiverTest {

    private lateinit var receiver: BackupMessageReceiver
    private lateinit var context: Context

    @Before
    fun setUp() {
        receiver = BackupMessageReceiver()
        context = mock(Context::class.java)
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    @Test
    fun `onReceive sends secure broadcast for non-Fujitsu`() {
        val intent = Intent("com.air.advantage.BACKUP_MESSAGE")
        mockStatic(FujitsuDetector::class.java).use { fujitsu ->
            fujitsu.`when`<Boolean> { FujitsuDetector.isFujitsuVariant(context) }.thenReturn(false)
            
            mockConstruction(Intent::class.java) { mockIntent, _ ->
                whenever(mockIntent.putExtra(any<String>(), any<String>())).thenReturn(mockIntent)
            }.use { mockedIntent ->
                receiver.onReceive(context, intent)

                val constructed = mockedIntent.constructed()
                assertEquals(1, constructed.size)
                
                verify(context).sendBroadcast(constructed[0], "com.air.android.secure_comms")
                verify(constructed[0]).putExtra("com.air.advantage.GET_DATA_REQUEST", "backupMessage")
                verify(constructed[0]).putExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE", "")
            }
        }
    }

    @Test
    fun `onReceive sends Fujitsu secure broadcast for Fujitsu variant`() {
        val intent = Intent("com.air.advantage.BACKUP_MESSAGE")
        mockStatic(FujitsuDetector::class.java).use { fujitsu ->
            fujitsu.`when`<Boolean> { FujitsuDetector.isFujitsuVariant(context) }.thenReturn(true)
            
            mockConstruction(Intent::class.java) { mockIntent, _ ->
                whenever(mockIntent.putExtra(any<String>(), any<String>())).thenReturn(mockIntent)
            }.use { mockedIntent ->
                receiver.onReceive(context, intent)

                val constructed = mockedIntent.constructed()
                assertEquals(1, constructed.size)
                
                verify(context).sendBroadcast(constructed[0], "com.air.android.secure_comms_fujitsu")
                verify(constructed[0]).putExtra("com.air.advantage.GET_DATA_REQUEST", "backupMessage")
                verify(constructed[0]).putExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU", "")
            }
        }
    }

    @Test
    fun `onReceive with null service does nothing`() {
        val intent = Intent("com.air.advantage.BACKUP_MESSAGE")
        UartForegroundService.instance = null
        mockStatic(FujitsuDetector::class.java).use { fujitsu ->
            fujitsu.`when`<Boolean> { FujitsuDetector.isFujitsuVariant(context) }.thenReturn(false)
            mockConstruction(Intent::class.java) { mockIntent, _ ->
                whenever(mockIntent.putExtra(any<String>(), any<String>())).thenReturn(mockIntent)
            }.use {
                receiver.onReceive(context, intent)
            }
        }
    }
}