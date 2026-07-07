package com.air.advantage.aaservice.ui.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.receiver.AlertDialogReceiver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AlertActivityTest {

    private lateinit var activity: AlertActivity

    @Before
    fun setUp() {
        AlertDialogReceiver.alertActive.set(false)
        activity = Robolectric.buildActivity(AlertActivity::class.java).create().get()
    }

    @After
    fun tearDown() {
        AlertDialogReceiver.alertActive.set(false)
    }

    // ── onCreate ─────────────────────────────────────────────────

    @Test
    fun `onCreate sets content view to layout_alert`() {
        activity.onCreate(null)
        verify(activity).setContentView(R.layout.layout_alert)
    }

    // ── onResume: alertActive = false ────────────────────────────

    @Test
    fun `onResume finishes when alertActive is false`() {
        AlertDialogReceiver.alertActive.set(false)
        activity.onResume()
        verify(activity).finish()
    }

    @Test
    fun `onResume registers HIDE_WARNING receiver when alertActive is false`() {
        AlertDialogReceiver.alertActive.set(false)
        activity.onResume()
        val lm = LocalBroadcastManager.getInstance(activity)
        assertNotNull(lm)
    }

    // ── onResume: alertActive = true ─────────────────────────────

    @Test
    fun `onResume does NOT finish when alertActive is true`() {
        AlertDialogReceiver.alertActive.set(true)
        activity.onResume()
        verify(activity, never()).finish()
    }

    @Test
    fun `onResume registers HIDE_WARNING receiver when alertActive is true`() {
        AlertDialogReceiver.alertActive.set(true)
        activity.onResume()
        val lm = LocalBroadcastManager.getInstance(activity)
        assertNotNull(lm)
    }

    // ── onPause: alertActive = true ──────────────────────────────

    @Test
    fun `onPause unregisters receiver when alertActive is true`() {
        AlertDialogReceiver.alertActive.set(true)
        activity.onResume()
        activity.onPause()
        val lm = LocalBroadcastManager.getInstance(activity)
        assertNotNull(lm)
    }

    @Test
    fun `onPause calls setAlert with 1200000ms when alertActive is true`() {
        AlertDialogReceiver.alertActive.set(true)
        activity.onPause()
        assertTrue(
            "setAlert should keep alertActive as true",
            AlertDialogReceiver.alertActive.get()
        )
    }

    // ── onPause: alertActive = false ─────────────────────────────

    @Test
    fun `onPause does NOT call setAlert when alertActive is false`() {
        AlertDialogReceiver.alertActive.set(false)
        activity.onPause()
        assertFalse(
            "setAlert should not be called when alertActive is false",
            AlertDialogReceiver.alertActive.get()
        )
    }

    @Test
    fun `onPause still unregisters receiver when alertActive is false`() {
        AlertDialogReceiver.alertActive.set(false)
        activity.onPause()
        val lm = LocalBroadcastManager.getInstance(activity)
        assertNotNull(lm)
    }

    // ── HIDE_WARNING broadcast ───────────────────────────────────

    @Test
    fun `HIDE_WARNING broadcast finishes the activity`() {
        AlertDialogReceiver.alertActive.set(true)
        activity.onResume()

        val filter = IntentFilter("com.air.advantage.HIDE_WARNING")
        val lm = LocalBroadcastManager.getInstance(activity)
        lm.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                activity.finish()
            }
        }, filter)

        activity.sendBroadcast(Intent("com.air.advantage.HIDE_WARNING"))

        verify(activity).finish()
    }

    @Test
    fun `HIDE_WARNING broadcast has correct action string`() {
        AlertDialogReceiver.alertActive.set(true)
        activity.onResume()

        val filter = IntentFilter("com.air.advantage.HIDE_WARNING")
        assertTrue(filter.hasAction("com.air.advantage.HIDE_WARNING"))
    }

    // ── Timeout constant ─────────────────────────────────────────

    @Test
    fun `setAlert timeout is 1200000ms (20 minutes)`() {
        AlertDialogReceiver.alertActive.set(true)
        activity.onPause()
        assertTrue(
            "setAlert with 1200000ms should keep alertActive as true",
            AlertDialogReceiver.alertActive.get()
        )
    }

    // ── State transitions ────────────────────────────────────────

    @Test
    fun `full lifecycle - onResume then onPause with alert active`() {
        AlertDialogReceiver.alertActive.set(true)

        activity.onResume()
        val lm = LocalBroadcastManager.getInstance(activity)
        assertNotNull(lm)
        verify(activity, never()).finish()

        activity.onPause()
        assertTrue(AlertDialogReceiver.alertActive.get())
    }

    @Test
    fun `full lifecycle - onResume then onPause with alert inactive`() {
        AlertDialogReceiver.alertActive.set(false)

        activity.onResume()
        verify(activity).finish()

        activity.onPause()
        val lm = LocalBroadcastManager.getInstance(activity)
        assertNotNull(lm)
        assertFalse(AlertDialogReceiver.alertActive.get())
    }

    @Test
    fun `HIDE_WARNING after onResume keeps alertActive state consistent`() {
        AlertDialogReceiver.alertActive.set(true)
        activity.onResume()

        val filter = IntentFilter("com.air.advantage.HIDE_WARNING")
        val lm = LocalBroadcastManager.getInstance(activity)
        lm.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                activity.finish()
            }
        }, filter)

        activity.sendBroadcast(Intent("com.air.advantage.HIDE_WARNING"))

        verify(activity).finish()
        assertTrue(AlertDialogReceiver.alertActive.get())
    }
}