package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.util.Log
import com.air.advantage.aaservice.util.CryptoHelper

class CanToCbNoPermissionReceiver : BaseReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val encrypted =
            intent.getByteArrayExtra("com.air.advantage.CAN_TO_CB_NO_PERMISSION") ?: run {
                Log.d(BCAST_TAG, "CanToCbNoPermission: missing encrypted extra")
                return
            }
        Log.d(BCAST_TAG, "CanToCbNoPermission: received ${encrypted.size} encrypted bytes")
        val decrypted =
            CryptoHelper.decrypt(encrypted) ?: run {
                Log.e(BCAST_TAG, "CanToCbNoPermission: decrypt failed")
                return
            }
        val canIds = String(decrypted)
        Log.d(BCAST_TAG, "CanToCbNoPermission: decrypted '$canIds'")
        service?.processCanIds(canIds) ?: Log.d(BCAST_TAG, "CanToCbNoPermission: no service instance, dropping")
    }
}
