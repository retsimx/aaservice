package com.air.advantage.aaservice.di

import com.air.advantage.aaservice.data.mailbox.MailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxWsConfig
import com.air.advantage.aaservice.data.mailbox.OkHttpMailboxWsClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object MailboxModule {

    @Provides
    @Singleton
    fun provideMailboxWsConfig(): MailboxWsConfig = MailboxWsConfig.DEFAULT

    @Provides
    @Singleton
    fun provideMailboxWsClient(config: MailboxWsConfig): MailboxWsClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return OkHttpMailboxWsClient(config = config, scope = scope)
    }
}
