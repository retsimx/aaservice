package com.air.advantage.aaservice.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceHelperAndroidTest {

    @Test
    fun objectHasAllExpectedMethods() {
        val methods = ServiceHelper::class.java.methods
        val methodNames = methods.map { it.name }.toSet()

        assertTrue("getUsbAccessory" in methodNames)
        assertTrue("isDeviceAdminActive" in methodNames)
        assertTrue("scheduleServiceStart" in methodNames)
        assertTrue("cancelScheduledServiceStart" in methodNames)
        assertTrue("startUartService" in methodNames)
        assertTrue("stopUartService" in methodNames)
        assertTrue("setVersionText" in methodNames)
    }

    @Test
    fun getUsbAccessory_returnsNull_whenNoUsbDevices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accessory = ServiceHelper.getUsbAccessory(context)
        assertNotNull(accessory)
    }

    @Test
    fun scheduleServiceStart_doesNotThrow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServiceHelper.scheduleServiceStart(context, "test.action", 5000)
    }

    @Test
    fun startUartService_doesNotThrow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServiceHelper.startUartService(context)
    }

    @Test
    fun stopUartService_doesNotThrow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServiceHelper.stopUartService(context)
    }
}