package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.domain.model.CanMessage

class CanMessageQueue {
    private val queue = ArrayDeque<CanMessage>()

    fun enqueue(message: CanMessage) {
        queue.addLast(message)
    }

    fun dequeue(): CanMessage? = queue.removeFirstOrNull()

    fun peek(): CanMessage? = queue.firstOrNull()

    fun isEmpty(): Boolean = queue.isEmpty()

    fun size(): Int = queue.size

    fun clear() {
        queue.clear()
    }

    /**
     * Builds "setCAN id1 id2 ... idN" frame string.
     * Max 25 CAN IDs per frame (from reference code).
     */
    fun buildCanFrame(): String {
        if (queue.isEmpty()) return ""
        val ids = queue.joinToString(" ") { it.id.toString() }
        return "setCAN $ids"
    }
}
