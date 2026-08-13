package com.air.advantage.aaservice.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.air.advantage.aaservice.ui.alert.AlertActivity
import java.util.concurrent.atomic.AtomicBoolean

class AlertDialogReceiver : BroadcastReceiver() {
    companion object {
        val alertActive = AtomicBoolean(false)
        private const val ALERT_REQUEST_CODE = 43678
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!alertActive.get()) {
            Log.d(BCAST_TAG, "AlertDialog: fired while inactive, hiding warning")
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("com.air.advantage.HIDE_WARNING"))
            return
        }
        Log.i(BCAST_TAG, "AlertDialog: alert active, launching AlertActivity")
        val alertIntent =
            Intent(context, AlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                action = "com.air.advantage.SHOW_ALERT"
            }
        context.startActivity(alertIntent)
    }

    fun setAlert(
        context: Context,
        active: Boolean,
        delayMs: Int,
    ) {
        Log.d(BCAST_TAG, "AlertDialog: setAlert active=$active delayMs=$delayMs")
        alertActive.set(active)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                ALERT_REQUEST_CODE,
                Intent(context, AlertDialogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )

        alarmManager.cancel(pendingIntent)

        if (active) {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent,
            )
        } else {
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("com.air.advantage.HIDE_WARNING"))
        }
    }
}
