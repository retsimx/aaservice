package com.air.advantage.aaservice.data.mailbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Builds a [MailboxWsClient] for a daemon WebSocket [url].
 *
 * Prefer this over a provide-time [MailboxWsConfig.DEFAULT] singleton when the
 * endpoint comes from prefs ([com.air.advantage.aaservice.util.PreferencesManager.daemonWsUrl]).
 * Implementations must not read SharedPreferences — callers pass the URL in.
 */
fun interface MailboxWsClientFactory {
    fun create(url: String): MailboxWsClient

    companion object {
        /**
         * Production OkHttp factory: one [CoroutineScope] per client, cancelled on
         * [MailboxWsClient.disconnect] so mode thrashing does not leak scopes.
         */
        fun okHttp(): MailboxWsClientFactory =
            MailboxWsClientFactory { url ->
                OkHttpMailboxWsClient(
                    config = MailboxWsConfig.forUrl(url),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    cancelScopeOnDisconnect = true,
                )
            }
    }
}
