package com.air.advantage.aaservice.domain.state

sealed class StateTransition {
    data class RespondToPoll(val frame: ByteArray) : StateTransition()
    data class SendData(val frame: ByteArray) : StateTransition()
    object SendConfig : StateTransition()
    object NoAction : StateTransition()
    object CloseConnection : StateTransition()
}
