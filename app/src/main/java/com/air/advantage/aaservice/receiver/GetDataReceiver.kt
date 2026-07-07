package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent

class GetDataReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra("com.air.advantage.GET_DATA") ?: return
        service?.broadcastData(tag)
    }
}