package com.air.advantage.aaservice.di

import com.air.advantage.aaservice.data.mailbox.MailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.data.mailbox.MailboxWsConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MailboxModule {

    @Provides
    @Singleton
    fun provideMailboxWsConfig(): MailboxWsConfig = MailboxWsConfig.DEFAULT

    /**
     * Creates [com.air.advantage.aaservice.data.mailbox.OkHttpMailboxWsClient]
     * instances for a caller-supplied URL
     * (typically [com.air.advantage.aaservice.util.PreferencesManager.daemonWsUrl]).
     * Does not read SharedPreferences.
     */
    @Provides
    @Singleton
    fun provideMailboxWsClientFactory(): MailboxWsClientFactory =
        MailboxWsClientFactory.okHttp()

    /** Optional singleton for injectors that do not need prefs URL yet. */
    @Provides
    @Singleton
    fun provideMailboxWsClient(factory: MailboxWsClientFactory): MailboxWsClient =
        factory.create(MailboxWsConfig.DEFAULT_URL)
}
