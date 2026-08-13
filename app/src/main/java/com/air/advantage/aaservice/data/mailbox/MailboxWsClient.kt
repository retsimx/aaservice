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
 * [sendWrite] / [sendCommand] generate a unique `msg_id`, wait for the matching
 * [MailboxInbound.Ack], and throw [MailboxAckTimeoutException] if no ack arrives
 * within [MailboxWsConfig.ackTimeoutMs].
 */
interface MailboxWsClient {
    val connectionState: StateFlow<MailboxConnectionState>

    /** Typed inbound frames (snapshot, event, ack, error). Unknown types are not emitted. */
    val incoming: SharedFlow<MailboxInbound>

    fun connect()

    fun disconnect()

    /**
     * Sends a `write` frame and awaits the matching ack.
     * [zone] is only for zone-bearing registers (03/04) and is omitted when null.
     * @throws MailboxAckTimeoutException if no ack within the config timeout
     * @throws IllegalStateException if the socket is not open
     */
    suspend fun sendWrite(
        register: String,
        payload: JSONObject,
        zone: Int? = null,
    ): MailboxInbound.Ack

    /**
     * Sends a `command` frame (e.g. [`MailboxCommandAction.RESYNC`]) and awaits
     * the matching ack.
     * @throws MailboxAckTimeoutException if no ack within the config timeout
     * @throws IllegalStateException if the socket is not open
     */
    suspend fun sendCommand(action: String): MailboxInbound.Ack
}

/** Thrown when [MailboxWsClient.sendWrite] / [MailboxWsClient.sendCommand] exceed [MailboxWsConfig.ackTimeoutMs]. */
class MailboxAckTimeoutException(
    val msgId: String,
) : Exception("Timed out waiting for mailbox ack msg_id=$msgId")
