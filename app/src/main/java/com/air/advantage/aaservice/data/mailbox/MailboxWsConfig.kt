package com.air.advantage.aaservice.data.mailbox

/**
 * Configuration for the mailbox WebSocket client ([MailboxWsClient] /
 * [OkHttpMailboxWsClient]).
 *
 * ## Defaults (design §5 / A1)
 * | Knob | Default | Constant |
 * |------|---------|----------|
 * | URL | `ws://127.0.0.1:2026/v1/mailbox-stream` | [DEFAULT_URL] |
 * | Ping keepalive | **30_000 ms (~30s)** | [DEFAULT_PING_INTERVAL_MS] |
 * | Reconnect initial delay | 1_000 ms | [DEFAULT_RECONNECT_INITIAL_DELAY_MS] |
 * | Reconnect max delay | 30_000 ms | [DEFAULT_RECONNECT_MAX_DELAY_MS] |
 * | Ack wait timeout | 10_000 ms | [DEFAULT_ACK_TIMEOUT_MS] |
 *
 * ## Session policy (enforced by the client, not by this data class)
 * - Ping keepalive uses [pingIntervalMs] (default ~30s).
 * - Unexpected disconnect → auto-reconnect with exponential backoff
 *   ([reconnectInitialDelayMs] → ×2 → [reconnectMaxDelayMs]).
 * - **No** auto-reconnect on explicit [MailboxWsClient.disconnect].
 * - [MailboxConnectionState.Connected] only after the first `snapshot`.
 * - **No** automatic USB fallback.
 *
 * @property url Daemon mailbox WebSocket endpoint; default [DEFAULT_URL].
 * @property pingIntervalMs OkHttp ping interval; default [DEFAULT_PING_INTERVAL_MS] (~30s).
 * @property reconnectInitialDelayMs First reconnect delay after an unexpected drop;
 *   default [DEFAULT_RECONNECT_INITIAL_DELAY_MS] (1s). Subsequent delays double
 *   until [reconnectMaxDelayMs].
 * @property reconnectMaxDelayMs Cap for exponential reconnect backoff;
 *   default [DEFAULT_RECONNECT_MAX_DELAY_MS] (30s).
 * @property ackTimeoutMs Max wait for a matching ack after [MailboxWsClient.sendWrite]
 *   / [MailboxWsClient.sendCommand] / [MailboxWsClient.sendRead]; default [DEFAULT_ACK_TIMEOUT_MS] (10s).
 */
data class MailboxWsConfig(
    val url: String = DEFAULT_URL,
    val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
    val reconnectInitialDelayMs: Long = DEFAULT_RECONNECT_INITIAL_DELAY_MS,
    val reconnectMaxDelayMs: Long = DEFAULT_RECONNECT_MAX_DELAY_MS,
    val ackTimeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_URL = "ws://127.0.0.1:2026/v1/mailbox-stream"

        /** Default OkHttp WebSocket ping keepalive (~30 seconds). */
        const val DEFAULT_PING_INTERVAL_MS = 30_000L

        /** Default first reconnect delay after an unexpected disconnect (1 second). */
        const val DEFAULT_RECONNECT_INITIAL_DELAY_MS = 1_000L

        /** Default cap for exponential reconnect backoff (30 seconds). */
        const val DEFAULT_RECONNECT_MAX_DELAY_MS = 30_000L

        /** Default ack wait for outbound mailbox frames (10 seconds). */
        const val DEFAULT_ACK_TIMEOUT_MS = 10_000L

        val DEFAULT = MailboxWsConfig()

        /** Config with [url] and default keepalive / reconnect / ack timeouts. */
        fun forUrl(url: String): MailboxWsConfig = DEFAULT.copy(url = url)
    }
}
