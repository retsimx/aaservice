package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.util.Log

class MessageToCbReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(BCAST_TAG, "MessageToCb: received")
        val s = service ?: run {
            Log.d(BCAST_TAG, "MessageToCb: no service instance, dropping")
            return
        }
        val wsMode = s.isWsMode()
        if (!wsMode && !s.deviceOpen.get()) {
            Log.d(BCAST_TAG, "MessageToCb: device not open, dropping")
            return
        }
        val message = intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB") ?: run {
            Log.d(BCAST_TAG, "MessageToCb: missing message extra")
            return
        }
        if (!message.contains("?")) {
            Log.d(BCAST_TAG, "MessageToCb: '$message' has no '?', dropping")
            return
        }
        val command = message.substring(0, message.indexOf("?"))
        // Stock USB filter: Light/Aircon/Activation/MySystem never hit UART.
        // WS mode still accepts setAircon?json= for mailbox_update mapping (A5).
        if (!wsMode &&
            (command.contains("Light") || command.contains("Aircon") ||
                command.contains("Activation") || command.contains("MySystem"))
        ) {
            Log.d(BCAST_TAG, "MessageToCb: blocked command '$command'")
            return
        }
        Log.d(BCAST_TAG, "MessageToCb: enqueueing '$message' (ws=$wsMode)")
        s.enqueueUartMessage(message)
    }
}
