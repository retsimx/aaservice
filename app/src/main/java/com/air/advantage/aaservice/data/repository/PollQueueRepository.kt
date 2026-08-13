package com.air.advantage.aaservice.data.repository

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class PollEntry(
    val tag: String,
    val frameTag: String,
)

class PollQueueRepository {
    private val pollQueue: MutableList<PollEntry> = mutableListOf()
    private val currentIndex = AtomicInteger(0)
    private val isPollActive = AtomicBoolean(true)
    private val canBusy = AtomicBoolean(false)

    fun initialize(isMyAir5: Boolean) {
        pollQueue.clear()
        val baseTags =
            listOf(
                "getSystemData",
                "getClock",
                "getZoneData?zone=1",
                "getZoneData?zone=2",
                "getZoneData?zone=3",
                "getZoneData?zone=4",
                "getZoneData?zone=5",
                "getZoneData?zone=6",
                "getZoneData?zone=7",
                "getZoneData?zone=8",
                "getZoneData?zone=9",
                "getZoneData?zone=10",
            )
        val scheduleTags =
            listOf(
                "getZoneTimer",
                "getScheduleData?schedule=1",
                "getScheduleData?schedule=2",
                "getScheduleData?schedule=3",
                "getScheduleData?schedule=4",
                "getScheduleData?schedule=5",
            )
        val tags = if (isMyAir5) baseTags else baseTags + scheduleTags
        tags.forEach { tag ->
            val crc = CrcCalculator.computeHex(tag)
            pollQueue.add(PollEntry(tag = tag, frameTag = "<U>$tag</U=$crc>"))
        }
        currentIndex.set(0)
    }

    fun currentPoll(): PollEntry? {
        if (!isPollActive.get() || pollQueue.isEmpty()) {
            return null
        }
        val idx = currentIndex.get()
        return if (idx in pollQueue.indices) pollQueue[idx] else null
    }

    fun advanceToNext(): PollEntry? {
        if (pollQueue.isEmpty()) {
            return null
        }
        val nextIndex = (currentIndex.get() + 1) % pollQueue.size
        currentIndex.set(nextIndex)
        return pollQueue[nextIndex]
    }

    fun reset() {
        currentIndex.set(0)
    }

    fun getIndex(): Int = currentIndex.get()

    fun suspendPolling() {
        isPollActive.set(false)
    }

    fun resumePolling() {
        isPollActive.set(true)
    }

    fun isCanBusy(): Boolean = canBusy.get()

    fun setCanBusy(busy: Boolean) {
        canBusy.set(busy)
    }

    fun queueSize(): Int = pollQueue.size

    fun isPollActive(): Boolean = isPollActive.get()
}
