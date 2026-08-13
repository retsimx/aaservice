package com.air.advantage.aaservice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.air.advantage.aaservice.R
import java.util.concurrent.atomic.AtomicBoolean

class RebootNotificationService : Service() {
    companion object {
        val rebootRequired = AtomicBoolean(false)
        private const val NOTIFICATION_CHANNEL_ID = "notification_channel_1"
        private const val NOTIFICATION_ID = 1234
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel(getString(R.string.service_name) + " Notification")
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.service_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            startForeground(
                NOTIFICATION_ID,
                Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Reboot Required")
                    .setSmallIcon(R.drawable.ic_info)
                    .setContentText("")
                    .build(),
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(
                NOTIFICATION_ID,
                Notification.Builder(this)
                    .setContentTitle("Reboot Required")
                    .setSmallIcon(R.drawable.ic_info)
                    .setContentText("")
                    .build(),
            )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        rebootRequired.set(true)
        return super.onStartCommand(intent, flags, startId)
    }
}
