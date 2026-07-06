package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent

class CanToCbReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val canIds = intent.getStringExtra("com.air.advantage.CAN_TO_CB") ?: return
        service?.processCanIds(canIds)
    }
}