package com.air.advantage.aaservice.data.repository

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CanStateRepository {
    private val retryCount = AtomicInteger(0)
    private val needsRetry = AtomicBoolean(false)
    @Volatile private var pendingCanFrame: String? = null

    fun recordRetry(): Int {
        val count = retryCount.incrementAndGet()
        if (count >= 3) {
            needsRetry.set(true)
        }
        return count
    }

    fun resetRetry() {
        retryCount.set(0)
        needsRetry.set(false)
    }

    fun needsRetry(): Boolean = needsRetry.get()

    fun setNeedsRetry(needsRetry: Boolean) {
        this.needsRetry.set(needsRetry)
    }

    fun storePendingCanFrame(frame: String) {
        pendingCanFrame = frame
    }

    fun getPendingCanFrame(): String? = pendingCanFrame

    fun clearPendingCanFrame() {
        pendingCanFrame = null
    }
}