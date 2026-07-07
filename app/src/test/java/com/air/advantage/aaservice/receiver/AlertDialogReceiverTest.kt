package com.air.advantage.aaservice.receiver

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.air.advantage.aaservice.ui.alert.AlertActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AlertDialogReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: AlertDialogReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = AlertDialogReceiver()
        AlertDialogReceiver.alertActive.set(false)
    }

    @After
    fun tearDown() {
        AlertDialogReceiver.alertActive.set(false)
    }

    @Test
    fun `onReceive when alert is not active sends HIDE_WARNING broadcast`() {
        AlertDialogReceiver.alertActive.set(false)
        val app = context as android.app.Application
        val shadowApp = shadowOf(app)

        receiver.onReceive(context, Intent())

        val sentBroadcasts = shadowApp.broadcastIntents
        assertTrue(sentBroadcasts.any { it.action == "com.air.advantage.HIDE_WARNING" })
    }

    @Test
    fun `onReceive when alert is active launches AlertActivity`() {
        AlertDialogReceiver.alertActive.set(true)
        val app = context as android.app.Application
        val shadowApp = shadowOf(app)

        receiver.onReceive(context, Intent())

        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull("AlertActivity should be started", startedIntent)
        assertEquals(AlertActivity::class.java.name, startedIntent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP, startedIntent.flags)
        assertEquals("com.air.advantage.SHOW_ALERT", startedIntent.action)
    }

    @Test
    fun `setAlert with active true sets active and schedules alarm`() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = shadowOf(alarmManager)

        receiver.setAlert(context, true, 5000)

        assertTrue(AlertDialogReceiver.alertActive.get())
        val alarm = shadowAlarm.nextScheduledAlarm
        assertNotNull("Alarm should be scheduled", alarm)
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarm!!.type)
    }

    @Test
    fun `setAlert with active false sets inactive and cancels alarm and sends HIDE_WARNING`() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = shadowOf(alarmManager)
        val shadowApp = shadowOf(context as android.app.Application)

        receiver.setAlert(context, true, 5000)
        assertNotNull(shadowAlarm.nextScheduledAlarm)

        receiver.setAlert(context, false, 5000)

        assertFalse(AlertDialogReceiver.alertActive.get())
        assertNull(shadowAlarm.nextScheduledAlarm)
        assertTrue(shadowApp.broadcastIntents.any { it.action == "com.air.advantage.HIDE_WARNING" })
    }
}