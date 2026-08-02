package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.domain.model.CanMessage

class CanMessageQueue {
    private val queue = ArrayDeque<CanMessage>()

    @Synchronized
    fun enqueue(message: CanMessage) {
        if (message.id != 0) {
            val messageStr = "setCAN ${message.id}"
            val prefix = messageStr.take(13)
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val existing = iterator.next()
                if (existing.id != 0 && "setCAN ${existing.id}".take(13) == prefix) {
                    iterator.remove()
                    break
                }
            }
        }
        queue.addLast(message)
    }

    @Synchronized
    fun dequeue(): CanMessage? = queue.removeFirstOrNull()

    @Synchronized
    fun peek(): CanMessage? = queue.firstOrNull()

    @Synchronized
    fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun clear() {
        queue.clear()
    }

    /**
     * Builds "setCAN id1 id2 ... idN" frame string.
     * Max 25 CAN IDs per frame (from reference code).
     */
    @Synchronized
    fun buildCanFrame(): String {
        if (queue.isEmpty()) return ""
        val ids = queue.joinToString(" ") { it.id.toString() }
        return "setCAN $ids"
    }
}
