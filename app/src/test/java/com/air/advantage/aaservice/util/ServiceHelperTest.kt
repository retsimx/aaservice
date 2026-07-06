package com.air.advantage.aaservice.util

import org.junit.Assert.*
import org.junit.Test

class ServiceHelperTest {

    @Test
    fun `object has all expected methods`() {
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
    fun `all methods have correct parameter counts`() {
        val methods = ServiceHelper::class.java.methods
        val methodMap = methods.associateBy { it.name }

        assertEquals(1, methodMap["getUsbAccessory"]?.parameterCount)
        assertEquals(1, methodMap["isDeviceAdminActive"]?.parameterCount)
        assertEquals(3, methodMap["scheduleServiceStart"]?.parameterCount)
        assertEquals(2, methodMap["cancelScheduledServiceStart"]?.parameterCount)
        assertEquals(2, methodMap["startUartService"]?.parameterCount)
        assertEquals(2, methodMap["stopUartService"]?.parameterCount)
        assertEquals(1, methodMap["setVersionText"]?.parameterCount)
    }

    @Test
    fun `scheduleServiceStart accepts Context, String, and Int`() {
        val method = ServiceHelper::class.java.getMethod(
            "scheduleServiceStart",
            android.content.Context::class.java,
            String::class.java,
            Int::class.javaPrimitiveType
        )
        assertNotNull(method)
    }

    @Test
    fun `startUartService accepts Context and optional String`() {
        val method = ServiceHelper::class.java.getMethod(
            "startUartService",
            android.content.Context::class.java,
            String::class.java
        )
        assertNotNull(method)
    }
}