package com.air.advantage.aaservice.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HardwareDetectorTest {
    @Test
    fun `detect returns MY_AIR5`() {
        assertEquals(HardwareDetector.HardwareType.MY_AIR5, HardwareDetector.detect())
    }

    @Test
    fun `typeBytes returns 0x31 and 0x37`() {
        val bytes = HardwareDetector.typeBytes()
        assertArrayEquals(byteArrayOf(0x31, 0x37), bytes)
    }

    @Test
    fun `appStoreBytes returns MyAir5`() {
        val bytes = HardwareDetector.appStoreBytes()
        assertEquals("MyAir5", String(bytes))
    }

    @Test
    fun `supportsSchedulePolling returns false`() {
        assertFalse(HardwareDetector.supportsSchedulePolling())
    }

    @Test
    fun `isForcedMyAir5 returns false`() {
        assertFalse(HardwareDetector.isForcedMyAir5)
    }

    @Test
    fun `HardwareType has all expected values`() {
        val expected =
            listOf(
                HardwareDetector.HardwareType.MY_AIR5,
                HardwareDetector.HardwareType.MY_AIR4,
                HardwareDetector.HardwareType.EZONE,
                HardwareDetector.HardwareType.VAMS,
                HardwareDetector.HardwareType.ZONE10E,
                HardwareDetector.HardwareType.LEGACY,
                HardwareDetector.HardwareType.UNKNOWN,
            )
        assertEquals(expected, HardwareDetector.HardwareType.values().toList())
    }
}
