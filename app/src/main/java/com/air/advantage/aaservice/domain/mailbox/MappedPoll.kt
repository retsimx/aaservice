package com.air.advantage.aaservice.domain.mailbox

/**
 * One synthesized MyAir5 poll-tag payload derived from a mailbox register.
 *
 * [payload] is the UART-style **inner** XML (no `<U>…</U={crc}>` wrapper) — the same
 * shape [com.air.advantage.aaservice.domain.state.UartDispatchEngine] caches and hands to
 * `broadcastData` on the USB path.
 */
data class MappedPoll(
    val tag: String,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MappedPoll) return false
        return tag == other.tag && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * tag.hashCode() + payload.contentHashCode()
}
