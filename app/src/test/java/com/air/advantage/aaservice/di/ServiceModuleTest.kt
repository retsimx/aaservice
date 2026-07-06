package com.air.advantage.aaservice.di

import android.content.Context
import com.air.advantage.aaservice.data.uart.UsbAccessoryDataSource
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class ServiceModuleTest {

    @Test
    fun testProvides() {
        val dataCache = ServiceModule.provideDataCacheRepository()
        assertNotNull(dataCache)

        val pollQueue = ServiceModule.providePollQueueRepository()
        assertNotNull(pollQueue)

        val canState = ServiceModule.provideCanStateRepository()
        assertNotNull(canState)

        val context = mock<Context>()
        val dataSource = ServiceModule.provideUartDataSource(context)
        assertNotNull(dataSource)
        assertTrue(dataSource is UsbAccessoryDataSource)
    }
}