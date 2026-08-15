package com.air.advantage.aaservice.service

import android.content.Intent
import com.air.advantage.aaservice.util.CryptoHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.capture
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PeriodicBroadcastTest {
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        val controller = Robolectric.buildService(UartForegroundService::class.java)
        service = spy(controller.get())

        doReturn("com.air.advantage.aaservice2").whenever(service).packageName
        whenever(service.registerReceiver(any(), any(), anyInt())).thenReturn(null)
        whenever(service.registerReceiver(any(), any(), anyOrNull(), anyOrNull(), anyInt())).thenReturn(null)
        doNothing().whenever(service).unregisterReceiver(any())
        doNothing().whenever(service).sendBroadcast(any<Intent>())
        doNothing().whenever(service).sendBroadcast(any<Intent>(), anyOrNull())

        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
    }

    private suspend fun CoroutineScope.runOnePeriodicIteration(): Intent {
        val job =
            launch {
                service.periodicInfoBroadcast()
            }
        delay(5500)
        job.cancel()

        val noPermCaptor = ArgumentCaptor.forClass(Intent::class.java)
        verify(service).sendBroadcast(noPermCaptor.capture())
        return noPermCaptor.value
    }

    @Test
    fun `periodicInfoBroadcast sends no-permission broadcast with correct action and component`() =
        runBlocking {
            val noPermIntent = runOnePeriodicIteration()

            assertEquals("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST", noPermIntent.action)
            assertEquals("com.air.advantage.zone10", noPermIntent.component?.packageName)
            assertEquals(
                "com.air.advantage.ReceiverDataUartForNoPermissionBroadcast",
                noPermIntent.component?.className,
            )
            assertEquals("aaServiceInfo", noPermIntent.getStringExtra("com.air.advantage.GET_DATA_REQUEST"))
        }

    @Test
    fun `periodicInfoBroadcast no-permission extra is encrypted ByteArray that decrypts to service info`() =
        runBlocking {
            val noPermIntent = runOnePeriodicIteration()

            assertNull(noPermIntent.getStringExtra("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST"))
            val encrypted = noPermIntent.getByteArrayExtra("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST")
            assertNotNull(encrypted)
            assertTrue("Encrypted bytes should be non-empty", encrypted!!.isNotEmpty())

            val decrypted = CryptoHelper.decrypt(encrypted)
            assertNotNull(decrypted)
            val json = String(decrypted!!, Charsets.UTF_8)
            assertTrue(json.contains("\"name\""))
            assertTrue(json.contains("\"version\""))
            assertTrue(json.contains("\"enabled\""))
        }
}
