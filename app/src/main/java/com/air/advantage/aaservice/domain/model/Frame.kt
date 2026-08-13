package com.air.advantage.aaservice.domain.model

sealed class Frame {
    data class Ping(val raw: ByteArray) : Frame()

    data class Ack(val raw: ByteArray) : Frame()

    data class Nack(val raw: ByteArray) : Frame()

    data class DataFrame(
        val requestTag: String,
        val payload: ByteArray,
        val raw: ByteArray,
    ) : Frame()

    data class GetCan(val raw: ByteArray) : Frame()

    data class Unknown(val raw: ByteArray) : Frame()

    data class CanInUse(val raw: ByteArray) : Frame()
}
