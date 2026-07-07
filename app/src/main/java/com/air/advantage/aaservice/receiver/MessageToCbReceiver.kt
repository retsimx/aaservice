package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent

class MessageToCbReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB") ?: return
        val command = if (message.contains("?")) {
            message.substring(0, message.indexOf("?"))
        } else {
            message
        }
        if (command.contains("Light") || command.contains("Aircon") ||
            command.contains("Activation") || command.contains("MySystem")) return
        service?.enqueueUartMessage(message)
    }
}