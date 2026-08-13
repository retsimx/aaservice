package com.air.advantage.aaservice.di

import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.util.PreferencesManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for [com.air.advantage.aaservice.service.UartForegroundService].
 *
 * The service is not `@AndroidEntryPoint` so Robolectric `buildService` tests keep
 * working without Hilt test rules; production resolves deps via this entry point,
 * with a manual PreferencesManager + factory fallback when Hilt is unavailable.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UartServiceEntryPoint {
    fun preferencesManager(): PreferencesManager

    fun mailboxWsClientFactory(): MailboxWsClientFactory
}
