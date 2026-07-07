package com.air.advantage.aaservice.di

import com.air.advantage.aaservice.data.uart.MockUartDataSource
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.data.uart.UsbAccessoryDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UartModule {

    @Provides
    @Singleton
    @Named("mock")
    fun provideMockUartDataSource(): UartDataSource = MockUartDataSource()

    @Provides
    @Singleton
    @Named("real")
    fun provideRealUartDataSource(): UartDataSource = UsbAccessoryDataSource()
}