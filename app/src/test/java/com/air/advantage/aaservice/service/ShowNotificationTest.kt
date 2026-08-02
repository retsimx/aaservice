package com.air.advantage.aaservice.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import com.air.advantage.aaservice.receiver.AlertDialogReceiver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ShowNotificationTest {

    private lateinit var controller: org.robolectric.android.controller.ServiceController<UartForegroundService>
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        controller = Robolectric.buildService(UartForegroundService::class.java)
        service = controller.create().get()
        UartForegroundService.instance = service
        AlertDialogReceiver.alertActive.set(false)
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
        AlertDialogReceiver.alertActive.set(false)
        RebootNotificationService.rebootRequired.set(false)
    }

    private fun notificationManager(): NotificationManager {
        return service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun foregroundTitle(): String? {
        return shadowOf(service).lastForegroundNotification
            ?.extras?.getString("android.title")
    }

    @Test
    fun `showNotification connected starts foreground with exact title`() {
        service.showNotification(connected = true)

        val shadowService = shadowOf(service)
        assertEquals(1234, shadowService.lastForegroundNotificationId)
        assertNotNull(shadowService.lastForegroundNotification)
        assertEquals("Connected to your system", foregroundTitle())
    }

    @Test
    fun `showNotification disconnected starts foreground with exact title`() {
        service.showNotification(connected = false)

        val shadowService = shadowOf(service)
        assertEquals(1234, shadowService.lastForegroundNotificationId)
        assertNotNull(shadowService.lastForegroundNotification)
        assertEquals("Not connected to your system", foregroundTitle())
    }

    @Test
    fun `showNotification with reboot required uses reboot title`() {
        RebootNotificationService.rebootRequired.set(true)

        service.showNotification(connected = true)

        assertEquals("Reboot required", foregroundTitle())
    }

    @Test
    fun `showNotification creates notification_channel_1 and deletes stale channel`() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel("AAService Notification", "stale", NotificationManager.IMPORTANCE_LOW)
        )
        assertFalse(shadowOf(nm).isChannelDeleted("AAService Notification"))

        service.showNotification(connected = true)

        val shadowNm = shadowOf(nm)
        val channel = shadowNm.notificationChannels
            .map { it as NotificationChannel }
            .single { it.id == "notification_channel_1" }
        assertEquals("AAService", channel.name.toString())
        assertEquals("AAService Notification Icon", channel.description.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertTrue(
            "stale channel should be deleted",
            shadowNm.isChannelDeleted("AAService Notification")
        )
    }

    @Test
    fun `showNotification connected deactivates alert and cancels pending alarm`() {
        service.showNotification(connected = true)

        assertFalse(AlertDialogReceiver.alertActive.get())
    }

    @Test
    fun `showNotification disconnected activates 60 second alert`() {
        service.showNotification(connected = false)

        assertTrue(AlertDialogReceiver.alertActive.get())

        val alarmManager = service.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = shadowOf(alarmManager).nextScheduledAlarm
        assertNotNull("60s alert should be scheduled", alarm)
        val delay = alarm!!.triggerAtTime - SystemClock.elapsedRealtime()
        assertEquals(60000L, delay)
    }

    @Test
    fun `showNotification repeated same value is a no-op`() {
        val app = service.application as android.app.Application
        shadowOf(app).clearBroadcastIntents()

        service.showNotification(connected = true)
        service.showNotification(connected = true)

        val hideWarnings = shadowOf(app).broadcastIntents.count {
            it.action == "com.air.advantage.HIDE_WARNING"
        }
        assertEquals(
            "second call should not repeat side effects",
            1,
            hideWarnings
        )
    }

    @Test
    fun `processing a valid data frame triggers connected notification`() {
        val tag = "getSystemData"
        val payload = "<type>17</type>".toByteArray()

        service.uartEventSink.onPollData(tag, payload)

        assertArrayEquals(payload, service.dataCache.get(tag))
        assertEquals("Connected to your system", foregroundTitle())
    }

    @Test
    fun `raw CAN delivery does not trigger connected notification`() {
        service.showNotification(false)

        service.uartEventSink.onRawCan("<U>getCAN zone1</U=00>".toByteArray())

        assertEquals("Not connected to your system", foregroundTitle())
    }
}
