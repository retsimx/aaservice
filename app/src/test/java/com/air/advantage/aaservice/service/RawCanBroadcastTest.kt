package com.air.advantage.aaservice.service

import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the getCAN / rawCan broadcast path in [RunnableParseMessage] by invoking
 * `run()` directly (deterministic — no executor scheduling involved).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RawCanBroadcastTest {

    private lateinit var service: UartForegroundService

    private val payload = "getCAN zone1".toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        val controller = Robolectric.buildService(UartForegroundService::class.java)
        service = spy(controller.get())

        doReturn("com.air.advantage.aaservice").whenever(service).packageName
        doNothing().whenever(service).sendBroadcast(any<Intent>())
        doNothing().whenever(service).sendBroadcast(any<Intent>(), anyOrNull())

        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
    }

    private fun runRawCan(message: String) {
        RunnableParseMessage(service, message.toByteArray(Charsets.UTF_8)).run()
    }

    @Test
    fun `secure broadcast carries rawCan extras and secure permission`() {
        runRawCan("getCAN zone1")

        verify(service).sendBroadcast(
            argThat<Intent> {
                action == UartForegroundService.MESSAGE_FROM_CB_SECURE &&
                getStringExtra(UartForegroundService.GET_DATA_REQUEST) == "rawCan" &&
                getStringExtra(UartForegroundService.MESSAGE_FROM_CB_SECURE) == "getCAN zone1"
            },
            eq(UartForegroundService.SECURE_PERMISSION)
        )
    }

    @Test
    fun `no-permission broadcast targets zone10 receiver with encrypted byte extra`() {
        runRawCan("getCAN zone1")

        val captor = argumentCaptor<Intent>()
        verify(service).sendBroadcast(captor.capture())

        val intent = captor.lastValue
        assertEquals(UartForegroundService.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST, intent.action)
        assertEquals(UartForegroundService.ZONE10_PACKAGE, intent.component?.packageName)
        assertEquals(UartForegroundService.ZONE10_NO_PERMISSION_RECEIVER, intent.component?.className)
        assertEquals("rawCan", intent.getStringExtra(UartForegroundService.GET_DATA_REQUEST))

        val encrypted = intent.getByteArrayExtra(UartForegroundService.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST)
        assertNotNull("Expected encrypted byte array extra", encrypted)
        assertTrue("Encrypted extra should be non-empty", encrypted!!.isNotEmpty())
    }

    @Test
    fun `same payload always rebroadcasts per reference CAS store`() {
        runRawCan("getCAN zone1")
        runRawCan("getCAN zone1")

        // Per run there is one secure (2-arg) and one no-permission (1-arg) broadcast.
        verify(service, times(2)).sendBroadcast(any<Intent>(), anyOrNull())
        verify(service, times(2)).sendBroadcast(any<Intent>())
    }

    @Test
    fun `gate drops payload not longer than 9 bytes`() {
        runRawCan("getCAN")

        verify(service, never()).sendBroadcast(any<Intent>(), anyOrNull())
        verify(service, never()).sendBroadcast(any<Intent>())
    }

    @Test
    fun `gate drops payload not starting at offset 0`() {
        runRawCan("xx getCAN 1")

        verify(service, never()).sendBroadcast(any<Intent>(), anyOrNull())
        verify(service, never()).sendBroadcast(any<Intent>())
    }

    @Test
    fun `byte 7 zero retries up to 3 times then broadcasts on 4th`() {
        val retryPayload = "getCAN 0zone1"

        repeat(3) { runRawCan(retryPayload) }

        verify(service, never()).sendBroadcast(any<Intent>(), anyOrNull())
        verify(service, never()).sendBroadcast(any<Intent>())

        runRawCan(retryPayload)

        verify(service).sendBroadcast(any<Intent>(), anyOrNull())
        verify(service).sendBroadcast(any<Intent>())
    }
}
