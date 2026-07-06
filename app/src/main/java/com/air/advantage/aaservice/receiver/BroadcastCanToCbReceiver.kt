package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent

class BroadcastCanToCbReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val canIds = intent.getStringExtra("com.air.advantage.BROADCAST_CAN_TO_CB") ?: return
        service?.enqueueCanIds(canIds)
    }
}