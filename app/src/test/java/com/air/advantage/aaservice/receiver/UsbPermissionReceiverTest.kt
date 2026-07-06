package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.util.ServiceHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

class UsbPermissionReceiverTest {

    private lateinit var receiver: UsbPermissionReceiver
    private lateinit var context: Context

    @Before
    fun setUp() {
        receiver = UsbPermissionReceiver()
        context = mock(Context::class.java)
    }

    @Test
    fun `onReceive with permission true schedules OPEN_DEVICE with 0 delay`() {
        mockStatic(ServiceHelper::class.java).use { mockedServiceHelper ->
            val receiverIntent = mock(Intent::class.java)
            whenever(receiverIntent.getBooleanExtra("permission", false)).thenReturn(true)
            
            receiver.onReceive(context, receiverIntent)
            
            mockedServiceHelper.verify {
                ServiceHelper.scheduleServiceStart(context, "com.air.advantage.OPEN_DEVICE", 0)
            }
        }
    }

    @Test
    fun `onReceive with permission false schedules REQUEST_PERMISSION with 200 delay`() {
        mockStatic(ServiceHelper::class.java).use { mockedServiceHelper ->
            val receiverIntent = mock(Intent::class.java)
            whenever(receiverIntent.getBooleanExtra("permission", false)).thenReturn(false)
            
            receiver.onReceive(context, receiverIntent)
            
            mockedServiceHelper.verify {
                ServiceHelper.scheduleServiceStart(context, "com.air.advantage.REQUEST_PERMISSION", 200)
            }
        }
    }

    @Test
    fun `onReceive with missing permission extra defaults to false`() {
        mockStatic(ServiceHelper::class.java).use { mockedServiceHelper ->
            val receiverIntent = mock(Intent::class.java)
            whenever(receiverIntent.getBooleanExtra("permission", false)).thenReturn(false)
            
            receiver.onReceive(context, receiverIntent)
            
            mockedServiceHelper.verify {
                ServiceHelper.scheduleServiceStart(context, "com.air.advantage.REQUEST_PERMISSION", 200)
            }
        }
    }

    @Test
    fun `receiver can be instantiated`() {
        assertNotNull(UsbPermissionReceiver())
    }

    @Test
    fun `receiver is a BroadcastReceiver`() {
        val receiver: Any = UsbPermissionReceiver()
        assertTrue(receiver is android.content.BroadcastReceiver)
    }
}