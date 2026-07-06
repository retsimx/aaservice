package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.util.FujitsuDetector

class BackupMessageReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isFujitsu = FujitsuDetector.isFujitsuVariant(context)
        val secureAction = if (isFujitsu)
            "com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU"
        else
            "com.air.advantage.MESSAGE_FROM_CB_SECURE"

        val responseIntent = Intent(secureAction).apply {
            putExtra("com.air.advantage.GET_DATA_REQUEST", "backupMessage")
            putExtra(secureAction, "")
        }

        val permission = if (isFujitsu)
            "com.air.android.secure_comms_fujitsu"
        else
            "com.air.android.secure_comms"

        context.sendBroadcast(responseIntent, permission)
    }
}