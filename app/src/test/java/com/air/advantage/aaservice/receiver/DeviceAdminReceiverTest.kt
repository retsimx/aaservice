package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class DeviceAdminReceiverTest {

    @Test
    fun receiver_can_be_instantiated() {
        assertNotNull(DeviceAdminReceiver())
    }

    @Test
    fun receiver_is_DeviceAdminReceiver() {
        val receiver: Any = DeviceAdminReceiver()
        assertTrue(receiver is android.app.admin.DeviceAdminReceiver)
    }

    @Test
    fun onEnabled_does_not_throw() {
        val context = mock(Context::class.java)
        val intent = mock(Intent::class.java)
        val receiver = DeviceAdminReceiver()

        receiver.onEnabled(context, intent)
    }

    @Test
    fun onDisabled_does_not_throw() {
        val context = mock(Context::class.java)
        val intent = mock(Intent::class.java)
        val receiver = DeviceAdminReceiver()

        receiver.onDisabled(context, intent)
    }
}