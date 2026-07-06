package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class PackageUpgradeReceiverTest {

    private lateinit var receiver: PackageUpgradeReceiver
    private lateinit var context: Context

    @Before
    fun setUp() {
        receiver = PackageUpgradeReceiver()
        context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("com.air.advantage.aaservice")
    }

    @Test
    fun receiver_can_be_instantiated() {
        assertNotNull(PackageUpgradeReceiver())
    }

    @Test
    fun receiver_is_BroadcastReceiver() {
        val receiver: Any = PackageUpgradeReceiver()
        assertTrue(receiver is android.content.BroadcastReceiver)
    }

    @Test
    fun onReceive_with_matching_package_starts_service() {
        val intent = mock(Intent::class.java)
        val data = mock(Uri::class.java)
        `when`(data.toString()).thenReturn("package:com.air.advantage.aaservice")
        `when`(intent.data).thenReturn(data)

        receiver.onReceive(context, intent)

        verify(context).startService(any<Intent>())
    }

    @Test
    fun onReceive_with_non_matching_package_returns_early() {
        val intent = mock(Intent::class.java)
        val data = mock(Uri::class.java)
        `when`(data.toString()).thenReturn("package:com.other.app")
        `when`(intent.data).thenReturn(data)

        receiver.onReceive(context, intent)

        verify(context, never()).startService(any<Intent>())
    }

    @Test
    fun onReceive_with_null_data_returns_early() {
        val intent = mock(Intent::class.java)
        `when`(intent.data).thenReturn(null)

        receiver.onReceive(context, intent)

        verify(context, never()).startService(any<Intent>())
    }

    @Test
    fun onReceive_sleeps_before_showing_activity() {
        val intent = mock(Intent::class.java)
        val data = mock(Uri::class.java)
        `when`(data.toString()).thenReturn("package:com.air.advantage.aaservice")
        `when`(intent.data).thenReturn(data)

        val startTime = System.currentTimeMillis()
        receiver.onReceive(context, intent)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Expected ~1000ms sleep, got ${elapsed}ms", elapsed >= 900)
    }

    @Test
    fun multiple_onReceive_calls_start_service_each_time() {
        val intent = mock(Intent::class.java)
        val data = mock(Uri::class.java)
        `when`(data.toString()).thenReturn("package:com.air.advantage.aaservice")
        `when`(intent.data).thenReturn(data)

        receiver.onReceive(context, intent)
        receiver.onReceive(context, intent)

        verify(context, times(2)).startService(any<Intent>())
    }
}