package com.air.advantage.aaservice.data.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CrcCalculatorTest {

    @Test
    fun `computeHex returns expected value for getSystemData`() {
        assertEquals("15", CrcCalculator.computeHex("getSystemData"))
    }

    @Test
    fun `computeHex returns ff for empty string`() {
        assertEquals("ff", CrcCalculator.computeHex(""))
    }

    @Test
    fun `computeHex returns expected value for ABC`() {
        assertEquals("67", CrcCalculator.computeHex("ABC"))
    }

    @Test
    fun `computeHex returns expected value for single byte`() {
        assertEquals("c1", CrcCalculator.computeHex("\u0001"))
    }

    @Test
    fun `compute with valid range returns correct CRC`() {
        // 4-byte array, end=3 processes bytes 0,1,2
        val data = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        assertEquals(103, CrcCalculator.compute(data, 0, 3))
    }

    @Test
    fun `compute with default end returns correct CRC over all bytes`() {
        // Default end = data.size is a valid exclusive upper bound and must iterate every byte
        val data = byteArrayOf(0x41, 0x42, 0x43)
        assertEquals(103, CrcCalculator.compute(data))
    }

    @Test
    fun `compute with invalid range where start greater than end returns 255`() {
        val data = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        assertEquals(255, CrcCalculator.compute(data, 2, 1))
    }

    @Test
    fun `compute with explicit end equal to data size matches default`() {
        val data = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        assertEquals(CrcCalculator.compute(data), CrcCalculator.compute(data, 0, data.size))
        assertEquals(54, CrcCalculator.compute(data, 0, 4))
    }

    @Test
    fun `compute with out-of-bounds end returns 255`() {
        val data = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        assertEquals(255, CrcCalculator.compute(data, 0, data.size + 1))
    }

    @Test
    fun `compute with empty array returns 255`() {
        assertEquals(255, CrcCalculator.compute(byteArrayOf()))
    }

    @Test
    fun `compute with partial range returns correct CRC`() {
        // 4-byte array, process only byte at index 2 (0x43)
        val data = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        // CRC of just 0x43: table[0 xor 67] = table[67] = 27, abs(27-255) = 228
        assertEquals(228, CrcCalculator.compute(data, 2, 3))
    }
}
