package com.air.advantage.aaservice.receiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.air.advantage.aaservice.util.CryptoHelper

class BackupMessageNoPermissionReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(BCAST_TAG, "BackupMessageNoPermission: received")
        val encrypted = CryptoHelper.encrypt("".toByteArray()) ?: run {
            Log.e(BCAST_TAG, "BackupMessageNoPermission: encrypt failed")
            return
        }
        val responseIntent = Intent("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST").apply {
            component = ComponentName(
                "com.air.advantage.zone10",
                "com.air.advantage.ReceiverDataUartForNoPermissionBroadcast"
            )
            putExtra("com.air.advantage.GET_DATA_REQUEST", "backupMessage")
            putExtra("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST", encrypted)
        }
        context.sendBroadcast(responseIntent)
        Log.d(BCAST_TAG, "BackupMessageNoPermission: sent ${encrypted.size} encrypted bytes")
    }
}