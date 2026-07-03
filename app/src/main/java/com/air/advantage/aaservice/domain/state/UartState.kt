package com.air.advantage.aaservice.domain.state

sealed class UartState {
    object Disconnected : UartState()
    object Connecting : UartState()
    object ConfigSent : UartState()
    data class Polling(val index: Int) : UartState()
    data class AwaitingResponse(
        val requestTag: String,
        val retryCount: Int,
        val isPollMessage: Boolean
    ) : UartState()
    object CanBusy : UartState()
    data class SendingCan(val messageIds: List<Int>) : UartState()
    data class Error(val message: String) : UartState()
}
