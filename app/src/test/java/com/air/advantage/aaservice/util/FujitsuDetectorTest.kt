package com.air.advantage.aaservice.util

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class FujitsuDetectorTest {
    @Test
    fun `isFujitsuVariant returns true when package contains fgassist`() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("com.example.fgassist.app")

        assertTrue(FujitsuDetector.isFujitsuVariant(context))
    }

    @Test
    fun `isFujitsuVariant returns false when package does not contain fgassist`() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("com.example.myair")

        assertFalse(FujitsuDetector.isFujitsuVariant(context))
    }

    @Test
    fun `isFujitsuVariant returns true for exact fgassist package`() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("fgassist")

        assertTrue(FujitsuDetector.isFujitsuVariant(context))
    }

    @Test
    fun `isFujitsuVariant returns false for null-like package`() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("com.example.app")

        assertFalse(FujitsuDetector.isFujitsuVariant(context))
    }
}
