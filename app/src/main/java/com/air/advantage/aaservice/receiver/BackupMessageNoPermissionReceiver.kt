package com.air.advantage.aaservice.receiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.util.CryptoHelper

class BackupMessageNoPermissionReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val encrypted = CryptoHelper.encrypt("".toByteArray()) ?: return
        val responseIntent = Intent("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST").apply {
            component = ComponentName(
                "com.air.advantage.zone10",
                "com.air.advantage.ReceiverDataUartForNoPermissionBroadcast"
            )
            putExtra("com.air.advantage.GET_DATA_REQUEST", "backupMessage")
            putExtra("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST", encrypted)
        }
        context.sendBroadcast(responseIntent)
    }
}