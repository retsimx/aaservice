package com.air.advantage.aaservice.receiver

import android.content.BroadcastReceiver
import com.air.advantage.aaservice.service.UartForegroundService

internal const val BCAST_TAG = "AAService2/Bcast"

abstract class BaseReceiver : BroadcastReceiver() {
    protected val service: UartForegroundService?
        get() = UartForegroundService.instance
}
