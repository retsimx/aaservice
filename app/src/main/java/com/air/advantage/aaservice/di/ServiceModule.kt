package com.air.advantage.aaservice.di

import android.content.Context
import com.air.advantage.aaservice.data.repository.CanStateRepository
import com.air.advantage.aaservice.data.repository.DataCacheRepository
import com.air.advantage.aaservice.data.repository.PollQueueRepository
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.data.uart.UsbAccessoryDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideDataCacheRepository(): DataCacheRepository = DataCacheRepository()

    @Provides
    @Singleton
    fun providePollQueueRepository(): PollQueueRepository = PollQueueRepository()

    @Provides
    @Singleton
    fun provideCanStateRepository(): CanStateRepository = CanStateRepository()

    @Provides
    @Singleton
    fun provideUartDataSource(@ApplicationContext context: Context): UartDataSource =
        UsbAccessoryDataSource(context)
}