package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent

class MessageToCbReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = service ?: return
        if (!s.deviceOpen.get()) return
        val message = intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB") ?: return
        if (!message.contains("?")) return
        val command = message.substring(0, message.indexOf("?"))
        if (command.contains("Light") || command.contains("Aircon") ||
            command.contains("Activation") || command.contains("MySystem")) return
        s.enqueueUartMessage(message)
    }
}