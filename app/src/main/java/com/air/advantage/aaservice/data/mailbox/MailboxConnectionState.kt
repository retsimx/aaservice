package com.air.advantage.aaservice.data.mailbox

/**
 * Observable connection lifecycle for the mailbox WebSocket client.
 *
 * [Connected] is intended after the first [MailboxInbound.Snapshot], not merely
 * on socket open. [Rejected] is terminal (no auto-reconnect), typically close
 * code 4009.
 *
 * ## Transport drops vs [Error]
 * Unexpected close / network failure intentionally emit [Disconnected] (then
 * auto-reconnect while the session is active) — **not** [Error]. That matches
 * design §3. [Error] is reserved for future client-local failures distinct from
 * transport drops and from protocol `error` frames on
 * [MailboxWsClient.incoming]. Downstream (A2) must treat drops as Disconnected.
 */
sealed class MailboxConnectionState {
    data object Idle : MailboxConnectionState()
    data object Connecting : MailboxConnectionState()
    data object Connected : MailboxConnectionState()

    /** Unexpected socket end while session active; auto-reconnect may follow. */
    data object Disconnected : MailboxConnectionState()

    data class Rejected(
        val code: Int,
        val reason: String? = null,
    ) : MailboxConnectionState()

    /**
     * Reserved for client-local failures (not currently emitted by
     * [OkHttpMailboxWsClient]). Transport drops use [Disconnected].
     */
    data class Error(
        val message: String,
    ) : MailboxConnectionState()
}
