package com.air.advantage.aaservice.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FujitsuDetectorAndroidTest {

    @Test
    fun isFujitsuVariant_returnsTrue_whenPackageContainsFgassist() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testContext = object : android.content.ContextWrapper(context) {
            override fun getPackageName(): String = "com.example.fgassist.app"
        }
        assertTrue(FujitsuDetector.isFujitsuVariant(testContext))
    }

    @Test
    fun isFujitsuVariant_returnsFalse_forStandardPackage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(FujitsuDetector.isFujitsuVariant(context))
    }
}