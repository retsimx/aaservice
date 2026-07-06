package com.air.advantage.aaservice.receiver

import android.app.AlarmManager
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class AlertDialogReceiverTest {

    private lateinit var receiver: AlertDialogReceiver
    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        receiver = AlertDialogReceiver()
        context = mock(Context::class.java)
        alarmManager = mock(AlarmManager::class.java)

        `when`(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
    }

    @After
    fun tearDown() {
        AlertDialogReceiver.alertActive.set(false)
    }

    @Test
    fun alertActive_is_initially_false() {
        AlertDialogReceiver.alertActive.set(false)
        assertFalse(AlertDialogReceiver.alertActive.get())
    }

    @Test
    fun alertActive_is_AtomicBoolean() {
        assertNotNull(AlertDialogReceiver.alertActive)
        assertTrue(AlertDialogReceiver.alertActive is java.util.concurrent.atomic.AtomicBoolean)
    }

    @Test
    fun ALERT_REQUEST_CODE_is_43678() {
        val field = AlertDialogReceiver::class.java.getDeclaredField("ALERT_REQUEST_CODE")
        field.isAccessible = true
        assertEquals(43678, field.get(null))
    }

    @Test
    fun receiver_can_be_instantiated() {
        assertNotNull(AlertDialogReceiver())
    }

    @Test
    fun receiver_is_BroadcastReceiver() {
        val receiver: Any = AlertDialogReceiver()
        assertTrue(receiver is android.content.BroadcastReceiver)
    }

    @Test
    fun setAlert_with_active_true_sets_true() {
        receiver.setAlert(context, true, 5000)
        assertTrue(AlertDialogReceiver.alertActive.get())
    }

    @Test
    fun setAlert_with_active_false_sets_false() {
        receiver.setAlert(context, false, 5000)
        assertFalse(AlertDialogReceiver.alertActive.get())
    }

    @Test
    fun multiple_setAlert_calls_update_state_correctly() {
        receiver.setAlert(context, true, 5000)
        assertTrue(AlertDialogReceiver.alertActive.get())

        receiver.setAlert(context, false, 5000)
        assertFalse(AlertDialogReceiver.alertActive.get())

        receiver.setAlert(context, true, 5000)
        assertTrue(AlertDialogReceiver.alertActive.get())
    }
}