package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent

class GetAllDataReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = service ?: return
        if (!s.deviceOpen.get()) return

        val baseTags = listOf(
            "getSystemData", "getClock",
            "getZoneData?zone=1", "getZoneData?zone=2", "getZoneData?zone=3",
            "getZoneData?zone=4", "getZoneData?zone=5", "getZoneData?zone=6",
            "getZoneData?zone=7", "getZoneData?zone=8", "getZoneData?zone=9",
            "getZoneData?zone=10"
        )
        baseTags.forEach { tag ->
            s.broadcastData(tag)
        }

        val scheduleTags = listOf(
            "getZoneTimer",
            "getScheduleData?schedule=1",
            "getScheduleData?schedule=2",
            "getScheduleData?schedule=3",
            "getScheduleData?schedule=4",
            "getScheduleData?schedule=5"
        )
        scheduleTags.forEach { tag ->
            s.broadcastData(tag)
        }

        scheduleTags.forEach { tag ->
            s.requestSinglePoll(tag)
        }
    }
}