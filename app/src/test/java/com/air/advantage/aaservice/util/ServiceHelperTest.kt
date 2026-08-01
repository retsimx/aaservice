package com.air.advantage.aaservice.util

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.service.UartForegroundService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ServiceHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun getUsbAccessory_returnsNull_whenNoUsbDevices() {
        val accessory = ServiceHelper.getUsbAccessory(context)
        assertNull(accessory)
    }

    @Test
    fun getUsbAccessory_returnsAccessory_whenDeviceConnected() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val shadowUsbManager = shadowOf(usbManager)
        val mockAccessory = mock(UsbAccessory::class.java)
        shadowUsbManager.setAttachedUsbAccessory(mockAccessory)

        val accessory = ServiceHelper.getUsbAccessory(context)
        assertEquals(mockAccessory, accessory)
    }

    @Test
    fun scheduleServiceStart_schedulesAlarm() {
        val action = "com.test.ACTION"
        val delayMs = 5000L
        ServiceHelper.scheduleServiceStart(context, action, delayMs)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = shadowOf(alarmManager)
        val alarm = shadowAlarm.nextScheduledAlarm
        assertNotNull("Alarm should be scheduled", alarm)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm!!.type)

        val shadowPendingIntent = shadowOf(alarm.operation)
        assertEquals(UartForegroundService::class.java.name, shadowPendingIntent.savedIntent.component?.className)
        assertEquals(action, shadowPendingIntent.savedIntent.action)
        assertEquals(R.string.app_name, shadowPendingIntent.requestCode)
        assertTrue((shadowPendingIntent.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
        assertEquals(0, shadowPendingIntent.flags and PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Test
    fun cancelScheduledServiceStart_cancelsAlarm() {
        val action = "com.test.ACTION"
        ServiceHelper.scheduleServiceStart(context, action, 5000)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = shadowOf(alarmManager)
        assertNotNull("Alarm should be scheduled first", shadowAlarm.nextScheduledAlarm)

        ServiceHelper.cancelScheduledServiceStart(context, action)
        assertNull("Alarm should be cancelled", shadowAlarm.nextScheduledAlarm)
    }

    @Test
    fun startUartService_startsService() {
        val app = context as android.app.Application
        shadowOf(app).clearStartedServices()

        ServiceHelper.startUartService(context)

        val serviceIntent = shadowOf(app).nextStartedService
        assertNotNull("Service should be started", serviceIntent)
        assertEquals(UartForegroundService::class.java.name, serviceIntent.component?.className)
    }

    @Test
    fun stopUartService_stopsService() {
        val app = context as android.app.Application
        shadowOf(app).clearStartedServices()

        ServiceHelper.stopUartService(context)

        val stoppedServiceIntent = shadowOf(app).nextStoppedService
        assertNotNull("Service should be stopped", stoppedServiceIntent)
        assertEquals(UartForegroundService::class.java.name, stoppedServiceIntent.component?.className)
    }

    @Test
    fun setVersionText_setsTextView() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val textView = TextView(activity).apply {
            id = R.id.version_number
        }
        activity.setContentView(textView)

        ServiceHelper.setVersionText(activity)
        assertTrue(textView.text.toString().startsWith("Version"))
    }
}