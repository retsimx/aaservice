package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.domain.model.CanMessage

class UartStateMachine {

    private var currentState: UartState = UartState.Disconnected
    private val canMessageQueue = CanMessageQueue()
    private var pollIndex = 0
    private var retryCount = 0
    private var canBusy = false

    fun getCurrentState(): UartState = currentState

    /**
     * Transition from Connecting to ConfigSent.
     */
    fun onConfigSent(): StateTransition {
        currentState = UartState.ConfigSent
        return StateTransition.NoAction
    }

    /**
     * Start poll cycle from ConfigSent.
     */
    fun onStartPoll(): StateTransition {
        currentState = UartState.Polling(0)
        pollIndex = 0
        return StateTransition.NoAction
    }

    /**
     * Send poll request at current index.
     * Returns SendData with the poll frame bytes.
     */
    fun onSendPoll(requestTag: String, frameBytes: ByteArray): StateTransition {
        currentState = UartState.AwaitingResponse(requestTag, 0, true)
        retryCount = 0
        return StateTransition.SendData(frameBytes)
    }

    /**
     * Valid response received matching the expected request tag.
     * Advances poll index and wraps around.
     */
    fun onValidResponse(responseTag: String): StateTransition {
        val state = currentState
        if (state is UartState.AwaitingResponse && state.requestTag == responseTag) {
            pollIndex++
            currentState = UartState.Polling(pollIndex)
            retryCount = 0
            canBusy = false
            return StateTransition.NoAction
        }
        return StateTransition.NoAction
    }

    /**
     * No response received - increment retry count.
     * After 3 retries, skip and advance poll.
     */
    fun onNoResponse(): StateTransition {
        val state = currentState
        if (state is UartState.AwaitingResponse) {
            retryCount++
            if (retryCount >= 3) {
                pollIndex++
                currentState = UartState.Polling(pollIndex)
                retryCount = 0
                return StateTransition.NoAction
            }
            currentState = UartState.AwaitingResponse(state.requestTag, retryCount, state.isPollMessage)
            return StateTransition.NoAction
        }
        return StateTransition.NoAction
    }

    /**
     * CAN message queued - interrupt polling.
     */
    fun onCanQueued(messageIds: List<Int>): StateTransition {
        currentState = UartState.SendingCan(messageIds)
        return StateTransition.NoAction
    }

    /**
     * CAN ack received - resume polling.
     */
    fun onCanAck(): StateTransition {
        currentState = UartState.Polling(pollIndex)
        canBusy = false
        return StateTransition.NoAction
    }

    /**
     * "CAN2 in use" response received.
     */
    fun onCanInUse(): StateTransition {
        canBusy = true
        currentState = UartState.CanBusy
        return StateTransition.NoAction
    }

    /**
     * Fatal error from any state.
     */
    fun onError(message: String): StateTransition {
        currentState = UartState.Error(message)
        return StateTransition.CloseConnection
    }

    /**
     * Get the current poll index.
     */
    fun getPollIndex(): Int = pollIndex

    /**
     * Check if CAN is busy.
     */
    fun isCanBusy(): Boolean = canBusy

    /**
     * Get the CAN message queue.
     */
    fun getCanMessageQueue(): CanMessageQueue = canMessageQueue

    /**
     * Reset state to Disconnected.
     */
    fun reset() {
        currentState = UartState.Disconnected
        pollIndex = 0
        retryCount = 0
        canBusy = false
        canMessageQueue.clear()
    }
}
