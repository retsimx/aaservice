package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.util.Log

class GetAllDataReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(BCAST_TAG, "GetAllData: received")
        val s = service ?: run {
            Log.d(BCAST_TAG, "GetAllData: no service instance, dropping")
            return
        }
        if (!s.deviceOpen.get()) {
            Log.d(BCAST_TAG, "GetAllData: device not open, dropping")
            return
        }

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
        Log.d(BCAST_TAG, "GetAllData: broadcast ${baseTags.size} base tags, polled ${scheduleTags.size} schedule tags")
    }
}