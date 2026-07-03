package com.air.advantage.aaservice.data.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrcCalculatorAndroidTest {
    @Test
    fun computeHex_returnsNonEmptyString() {
        val result = CrcCalculator.computeHex("test")
        assertTrue("Result should be non-empty", result.isNotEmpty())
        assertEquals("Result should be 2 chars", 2, result.length)
    }

    @Test
    fun computeHex_emptyString_returnsFf() {
        assertEquals("ff", CrcCalculator.computeHex(""))
    }
}
