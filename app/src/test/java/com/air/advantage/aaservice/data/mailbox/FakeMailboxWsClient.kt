package com.air.advantage.aaservice.data.mailbox

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * In-memory [MailboxWsClient] for unit tests of interface consumers.
 * Does not open a network socket; callers drive [connectionState] / [incoming] directly.
 */
class FakeMailboxWsClient : MailboxWsClient {
    private val _connectionState =
        MutableStateFlow<MailboxConnectionState>(MailboxConnectionState.Idle)
    override val connectionState: StateFlow<MailboxConnectionState> =
        _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<MailboxInbound>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<MailboxInbound> = _incoming.asSharedFlow()

    var connectCalls: Int = 0
        private set
    var disconnectCalls: Int = 0
        private set
    val sentUpdates = mutableListOf<Pair<String, JSONObject>>()
    val sentResyncs = mutableListOf<Unit>()

    var nextUpdateAck: MailboxInbound.Ack = successAck("fake-update")
    var nextResyncAck: MailboxInbound.Ack = successAck("fake-resync")

    /**
     * When true, [connect] moves straight to [MailboxConnectionState.Connected]
     * so [com.air.advantage.aaservice.service.ModeSwitchCoordinator] snapshot
     * wait can complete in Robolectric without a manual [emitState].
     */
    var emitConnectedOnConnect: Boolean = false

    fun emitState(state: MailboxConnectionState) {
        _connectionState.value = state
    }

    fun emitIncoming(inbound: MailboxInbound) {
        check(_incoming.tryEmit(inbound)) { "incoming buffer overflow" }
    }

    override fun connect() {
        connectCalls++
        _connectionState.value = if (emitConnectedOnConnect) {
            MailboxConnectionState.Connected
        } else {
            MailboxConnectionState.Connecting
        }
    }

    override fun disconnect() {
        disconnectCalls++
        _connectionState.value = MailboxConnectionState.Idle
    }

    override suspend fun sendUpdate(register: String, payload: JSONObject): MailboxInbound.Ack {
        sentUpdates += register to payload
        return nextUpdateAck
    }

    override suspend fun sendResync(): MailboxInbound.Ack {
        sentResyncs += Unit
        return nextResyncAck
    }

    companion object {
        fun successAck(msgId: String): MailboxInbound.Ack = MailboxInbound.Ack(
            msgId = msgId,
            status = MailboxAckStatus.SUCCESS,
            reason = null,
            raw = JSONObject()
                .put("type", MailboxMessageType.ACK)
                .put("msg_id", msgId)
                .put("status", "success"),
        )
    }
}
