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

    private val _daemonStatus = MutableSharedFlow<MailboxInbound.Status>(replay = 1)
    override val daemonStatus: SharedFlow<MailboxInbound.Status> =
        _daemonStatus.asSharedFlow()

    var connectCalls: Int = 0
        private set
    var disconnectCalls: Int = 0
        private set
    val sentWrites = mutableListOf<Triple<String, JSONObject, Int?>>()
    val sentCommands = mutableListOf<String>()
    val sentReads = mutableListOf<Pair<String, Int?>>()

    var nextWriteAck: MailboxInbound.Ack = successAck("fake-write")
    var nextCommandAck: MailboxInbound.Ack = successAck("fake-command")
    var nextReadOutcome: ReadOutcome =
        ReadOutcome.Value(
            MailboxInbound.ReadResult(
                msgId = "fake-read",
                unitType = null,
                unitId = null,
                register = null,
                zone = null,
                payload = JSONObject(),
                raw =
                    JSONObject()
                        .put("type", MailboxMessageType.READ_RESULT)
                        .put("msg_id", "fake-read"),
            ),
        )

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

    fun emitDaemonStatus(status: MailboxInbound.Status) {
        check(_daemonStatus.tryEmit(status)) { "daemonStatus buffer overflow" }
    }

    override fun connect() {
        connectCalls++
        _connectionState.value =
            if (emitConnectedOnConnect) {
                MailboxConnectionState.Connected
            } else {
                MailboxConnectionState.Connecting
            }
    }

    override fun disconnect() {
        disconnectCalls++
        _connectionState.value = MailboxConnectionState.Idle
    }

    override suspend fun sendWrite(
        register: String,
        payload: MailboxPayload,
        zone: Int?,
    ): MailboxInbound.Ack {
        val json =
            when (payload) {
                is MailboxPayload.Typed -> payload.payload
                is MailboxPayload.RawHex -> JSONObject().put("_rawHex", payload.hex)
            }
        sentWrites += Triple(register, json, zone)
        return nextWriteAck
    }

    override suspend fun sendRead(
        register: String,
        zone: Int?,
    ): ReadOutcome {
        sentReads += register to zone
        return nextReadOutcome
    }

    override suspend fun sendCommand(action: String): MailboxInbound.Ack {
        sentCommands += action
        return nextCommandAck
    }

    companion object {
        fun successAck(msgId: String): MailboxInbound.Ack =
            MailboxInbound.Ack(
                msgId = msgId,
                status = MailboxAckStatus.SUCCESS,
                reason = null,
                raw =
                    JSONObject()
                        .put("type", MailboxMessageType.ACK)
                        .put("msg_id", msgId)
                        .put("status", "success"),
            )
    }
}
