package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent

class GetAllDataReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        service?.requestFullPoll()
    }
}