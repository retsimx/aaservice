package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.util.Log

class CanToCbReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val canIds = intent.getStringExtra("com.air.advantage.CAN_TO_CB") ?: run {
            Log.d(BCAST_TAG, "CanToCb: missing canIds extra")
            return
        }
        Log.d(BCAST_TAG, "CanToCb: received '$canIds'")
        service?.processCanIds(canIds) ?: Log.d(BCAST_TAG, "CanToCb: no service instance, dropping")
    }
}