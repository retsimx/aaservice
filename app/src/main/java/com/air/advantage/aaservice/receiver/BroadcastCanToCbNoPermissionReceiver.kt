package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.util.CryptoHelper

class BroadcastCanToCbNoPermissionReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val encrypted = intent.getByteArrayExtra("com.air.advantage.BROADCAST_CAN_TO_CB_NO_PERMISSION") ?: return
        val decrypted = CryptoHelper.decrypt(encrypted) ?: return
        service?.enqueueCanIds(String(decrypted))
    }
}