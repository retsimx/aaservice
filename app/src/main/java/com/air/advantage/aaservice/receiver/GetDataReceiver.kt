package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.util.Log

class GetDataReceiver : BaseReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val tag =
            intent.getStringExtra("com.air.advantage.GET_DATA") ?: run {
                Log.d(BCAST_TAG, "GetData: missing tag extra")
                return
            }
        Log.d(BCAST_TAG, "GetData: requested '$tag'")
        service?.broadcastData(tag) ?: Log.d(BCAST_TAG, "GetData: no service instance, dropping")
    }
}
