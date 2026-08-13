package com.air.advantage.aaservice.data.mailbox

/**
 * Observable connection lifecycle for the mailbox WebSocket client.
 *
 * [Connected] is intended after the first [MailboxInbound.Snapshot], not merely
 * on socket open. Unexpected closes fall through to [Disconnected] and
 * auto-reconnect while the session is active.
 *
 * ## Transport drops vs [Error]
 * Unexpected close / network failure intentionally emit [Disconnected] (then
 * auto-reconnect while the session is active) — **not** [Error]. That matches
 * design §3. [Error] is reserved for future client-local failures distinct from
 * transport drops and from protocol `error` frames on
 * [MailboxWsClient.incoming]. Downstream (A2) must treat drops as Disconnected.
 *
 * ## Link health vs connection state
 * Daemon/link health (broker `status` frames) is exposed via
 * [MailboxWsClient.daemonStatus], not through this state type — a live socket
 * with a degraded link still reports [Connected] here.
 */
sealed class MailboxConnectionState {
    data object Idle : MailboxConnectionState()

    data object Connecting : MailboxConnectionState()

    data object Connected : MailboxConnectionState()

    /** Unexpected socket end while session active; auto-reconnect may follow. */
    data object Disconnected : MailboxConnectionState()

    /**
     * Reserved for client-local failures (not currently emitted by
     * [OkHttpMailboxWsClient]). Transport drops use [Disconnected].
     */
    data class Error(
        val message: String,
    ) : MailboxConnectionState()
}
