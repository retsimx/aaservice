package com.air.advantage.aaservice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.air.advantage.aaservice.service.RebootNotificationService
import com.air.advantage.aaservice.ui.main.MainActivity

class PackageUpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val dataUri = intent.data ?: return
        if (dataUri.toString() != "package:${context.packageName}") return
        Log.i(BCAST_TAG, "PackageUpgrade: own package replaced, scheduling reboot notice and relaunch")

        val rebootIntent = Intent(context, RebootNotificationService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(rebootIntent)
        } else {
            context.startService(rebootIntent)
        }

        SystemClock.sleep(1000L)

        val mainIntent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            }
        context.startActivity(mainIntent)
    }
}
