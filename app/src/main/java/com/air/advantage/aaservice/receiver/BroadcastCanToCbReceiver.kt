package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.util.Log

class BroadcastCanToCbReceiver : BaseReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val canIds =
            intent.getStringExtra("com.air.advantage.BROADCAST_CAN_TO_CB") ?: run {
                Log.d(BCAST_TAG, "BroadcastCanToCb: missing canIds extra")
                return
            }
        Log.d(BCAST_TAG, "BroadcastCanToCb: received '$canIds'")
        service?.enqueueBroadcastCanIds(canIds) ?: Log.d(BCAST_TAG, "BroadcastCanToCb: no service instance, dropping")
    }
}
