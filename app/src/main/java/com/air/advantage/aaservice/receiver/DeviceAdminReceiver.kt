package com.air.advantage.aaservice.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.ui.main.MainActivity
import com.air.advantage.aaservice.util.ServiceHelper

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        ServiceHelper.startUartService(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        ServiceHelper.stopUartService(context, ServiceHelper.ACTION_CLOSE_DEVICE)
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(mainIntent)
    }
}