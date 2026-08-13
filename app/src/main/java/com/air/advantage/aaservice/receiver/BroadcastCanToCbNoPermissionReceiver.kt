package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.util.Log
import com.air.advantage.aaservice.util.CryptoHelper

class BroadcastCanToCbNoPermissionReceiver : BaseReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val encrypted =
            intent.getByteArrayExtra("com.air.advantage.BROADCAST_CAN_TO_CB_NO_PERMISSION") ?: run {
                Log.d(BCAST_TAG, "BroadcastCanToCbNoPermission: missing encrypted extra")
                return
            }
        Log.d(BCAST_TAG, "BroadcastCanToCbNoPermission: received ${encrypted.size} encrypted bytes")
        val decrypted =
            CryptoHelper.decrypt(encrypted) ?: run {
                Log.e(BCAST_TAG, "BroadcastCanToCbNoPermission: decrypt failed")
                return
            }
        val canIds = String(decrypted)
        Log.d(BCAST_TAG, "BroadcastCanToCbNoPermission: decrypted '$canIds'")
        service?.enqueueBroadcastCanIds(canIds)
            ?: Log.d(BCAST_TAG, "BroadcastCanToCbNoPermission: no service instance, dropping")
    }
}
