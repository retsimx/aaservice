package com.air.advantage.aaservice.ui.alert

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.air.advantage.aaservice.R
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
class AlertActivityTest {

    @Before
    fun setUp() {
        AlertDialogReceiver.alertActive.set(false)
    }

    @After
    fun tearDown() {
        AlertDialogReceiver.alertActive.set(false)
    }

    @Test
    fun `onCreate sets content view to layout_alert`() {
        AlertDialogReceiver.alertActive.set(true)
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity.findViewById(R.id.textView))
    }

    @Test
    fun `onResume finishes when alertActive is false`() {
        AlertDialogReceiver.alertActive.set(false)
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `onResume does NOT finish when alertActive is true`() {
        AlertDialogReceiver.alertActive.set(true)
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `onPause unregisters receiver when alertActive is true`() {
        AlertDialogReceiver.alertActive.set(true)
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        val activity = controller.get()
        controller.pause()

        LocalBroadcastManager.getInstance(activity).sendBroadcastSync(Intent("com.air.advantage.HIDE_WARNING"))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `onPause calls setAlert when alertActive is true`() {
        AlertDialogReceiver.alertActive.set(true)
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        val activity = controller.get()
        controller.pause()

        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = shadowOf(alarmManager)
        val alarm = shadowAlarm.nextScheduledAlarm
        assertNotNull("Alarm should be scheduled", alarm)
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarm!!.type)

        val shadowPendingIntent = shadowOf(alarm.operation)
        assertEquals(AlertDialogReceiver::class.java.name, shadowPendingIntent.savedIntent.component?.className)
    }

    @Test
    fun `onPause does NOT call setAlert when alertActive is false`() {
        AlertDialogReceiver.alertActive.set(true)
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        val activity = controller.get()

        AlertDialogReceiver.alertActive.set(false)
        controller.pause()

        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = shadowOf(alarmManager)
        val alarm = shadowAlarm.nextScheduledAlarm
        assertNull("Alarm should NOT be scheduled", alarm)
    }

    @Test
    fun `HIDE_WARNING broadcast finishes the activity`() {
        AlertDialogReceiver.alertActive.set(true)
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        val activity = controller.get()

        LocalBroadcastManager.getInstance(activity).sendBroadcastSync(Intent("com.air.advantage.HIDE_WARNING"))
        assertTrue(activity.isFinishing)
    }
}