package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent

class MessageToCbReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB") ?: return
        if (message.contains("Light") || message.contains("Aircon") ||
            message.contains("Activation") || message.contains("MySystem")) return
        service?.enqueueUartMessage(message)
    }
}