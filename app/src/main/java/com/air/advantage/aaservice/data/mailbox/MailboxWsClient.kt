package com.air.advantage.aaservice.data.mailbox

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Injectable client for the cb-daemon mailbox WebSocket.
 *
 * ## Session policy
 * - **Keepalive:** OkHttp WebSocket pings at [MailboxWsConfig.pingIntervalMs]
 *   (default ~30s via [MailboxWsConfig.DEFAULT_PING_INTERVAL_MS]).
 * - [connect] → [MailboxConnectionState.Connecting], then open the socket.
 * - [MailboxConnectionState.Connected] only after the first `snapshot`
 *   ([MailboxInbound.Snapshot]) — not on bare socket open.
 * - Unexpected close / network drop → [MailboxConnectionState.Disconnected], then
 *   **auto-reconnect** with exponential backoff
 *   ([MailboxWsConfig.reconnectInitialDelayMs] → double →
 *   [MailboxWsConfig.reconnectMaxDelayMs]). Drops use Disconnected, not
 *   [MailboxConnectionState.Error] (see that type’s KDoc).
 * - Close code **4009** (single-client limit) → [MailboxConnectionState.Rejected];
 *   **no** auto-reconnect.
 * - Explicit [disconnect] → cancel reconnect, close socket →
 *   [MailboxConnectionState.Idle]; **no** auto-reconnect.
 * - **No automatic USB fallback** — this client never opens a USB accessory.
 *
 * [connect] / [disconnect] are asynchronous; observe [connectionState].
 *
 * Protocol `error` frames are emitted on [incoming] and do not alone fail the socket.
 * Unknown inbound `type` values are ignored.
 *
 * [sendWrite] / [sendCommand] / [sendRead] generate a unique `msg_id`, wait for
 * the matching [MailboxInbound.Ack] (write/command) or [ReadOutcome]
 * (read), and throw [MailboxAckTimeoutException] if no reply arrives within
 * [MailboxWsConfig.ackTimeoutMs].
 */
interface MailboxWsClient {
    val connectionState: StateFlow<MailboxConnectionState>

    /** Typed inbound frames (snapshot, event, ack, error). Unknown types are not emitted. */
    val incoming: SharedFlow<MailboxInbound>

    /**
     * Broker link-state frames ([MailboxInbound.Status]), replay=1. Loosely typed
     * (`state` / `detail` stay raw); semantic mapping is owned by the consumer (B-6).
     * Status frames also arrive on [incoming]; [daemonStatus] is a dedicated,
     * loss-tolerant channel that never fails the socket on overflow.
     */
    val daemonStatus: SharedFlow<MailboxInbound.Status>

    fun connect()

    fun disconnect()

    /**
     * Sends a `write` frame and awaits the matching ack.
     * [payload] is the register payload — typed JSON object or raw hex string.
     * [zone] is only for zone-bearing registers (03/04) and is omitted when null.
     * @throws MailboxAckTimeoutException if no ack within the config timeout
     * @throws IllegalStateException if the socket is not open
     */
    suspend fun sendWrite(
        register: String,
        payload: MailboxPayload,
        zone: Int? = null,
    ): MailboxInbound.Ack

    /**
     * Sends a `read` frame and awaits the outcome: a matching
     * [MailboxInbound.ReadResult] on success, or an error ack (e.g. reason
     * `"register 03 has no value"`, `"read timeout"`) on failure.
     * [zone] is only for zone-bearing registers (03/04) and is omitted when null.
     * @throws MailboxAckTimeoutException if no reply within the config timeout
     * @throws IllegalStateException if the socket is not open
     */
    suspend fun sendRead(register: String, zone: Int? = null): ReadOutcome

    /**
     * Sends a `command` frame (e.g. [`MailboxCommandAction.RESYNC`]) and awaits
     * the matching ack.
     * @throws MailboxAckTimeoutException if no ack within the config timeout
     * @throws IllegalStateException if the socket is not open
     */
    suspend fun sendCommand(action: String): MailboxInbound.Ack
}

/**
 * Outcome of [MailboxWsClient.sendRead].
 *
 * Success → the broker's [MailboxInbound.ReadResult]; failure → an error
 * [MailboxInbound.Ack] whose [MailboxInbound.Ack.reason] carries the daemon's
 * explanation (e.g. `"register 03 has no value"`, `"read timeout"`).
 */
sealed interface ReadOutcome {
    data class Value(val result: MailboxInbound.ReadResult) : ReadOutcome
    data class Error(val ack: MailboxInbound.Ack) : ReadOutcome
}

/** Thrown when [MailboxWsClient.sendWrite] / [MailboxWsClient.sendCommand] / [MailboxWsClient.sendRead] exceed [MailboxWsConfig.ackTimeoutMs]. */
class MailboxAckTimeoutException(
    val msgId: String,
) : Exception("Timed out waiting for mailbox ack msg_id=$msgId")
