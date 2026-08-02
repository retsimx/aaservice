package com.air.advantage.aaservice.service

import android.content.Intent
import com.air.advantage.aaservice.domain.model.CanMessage
import com.air.advantage.aaservice.domain.state.UartState
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
 * Direct, deterministic tests for the non-getCAN branches of [RunnableParseMessage.run]:
 * ack/nack parity (reference `a.d()/a.f() > 0`), Unknown, outbound-queue match,
 * poll-response match, and getSystemData enrichment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RunnableParseMessageTest {

    private lateinit var service: UartForegroundService

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

    private fun runParse(payload: String) {
        RunnableParseMessage(service, payload.toByteArray(Charsets.UTF_8)).run()
    }

    // ── ack/nack parity — reference k.java uses a.f()/a.d() > 0 (match NOT at index 0) ──

    @Test
    fun `ack at offset 0 is NOT treated as ack per reference`() {
        runParse("<ack>1</ack>")

        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
        verify(service, never()).sendBroadcast(any<Intent>(), anyOrNull())
    }

    @Test
    fun `nack at offset 0 is NOT treated as nack per reference`() {
        service.messageSent.set(true)
        runParse("<ack>0</ack>")

        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
        assertFalse(service.pollQueue.isCanBusy())
    }

    @Test
    fun `ack at offset greater than 0 triggers onCanAck`() {
        runParse("prefix<ack>1</ack>")

        assertEquals(UartState.Polling(0), service.stateMachine.getCurrentState())
    }

    @Test
    fun `nack with messageSent and unknown sets canBusy and returns`() {
        service.messageSent.set(true)
        runParse("prefix<ack>0</ack><request>Unknown</request>")

        assertTrue(service.pollQueue.isCanBusy())
        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
    }

    @Test
    fun `nack with messageSent and non-unknown falls through without canBusy`() {
        service.messageSent.set(true)
        runParse("prefix<ack>0</ack>")

        assertFalse(service.pollQueue.isCanBusy())
        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
    }

    @Test
    fun `nack without messageSent falls through without canBusy`() {
        runParse("prefix<ack>0</ack>")

        assertFalse(service.pollQueue.isCanBusy())
        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
    }

    // ── Unknown handling ──

    @Test
    fun `unknown response returns early without state change`() {
        runParse("<request>Unknown</request>")

        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
        verify(service, never()).sendBroadcast(any<Intent>(), anyOrNull())
    }

    // ── outbound-queue match ──

    @Test
    fun `matching request dequeues outbound queue message`() {
        service.lastSentMessage.set("<U>setTemp 20</U=ab>")
        service.canQueue.enqueue(CanMessage(id = 7, data = "setTemp 20"))

        runParse("<request>setTemp 20</request>")

        assertTrue(service.canQueue.isEmpty())
        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
    }

    @Test
    fun `matching request with empty queue returns without dequeue error state`() {
        service.lastSentMessage.set("<U>setTemp 20</U=ab>")

        runParse("<request>setTemp 20</request>")

        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
    }

    @Test
    fun `mismatched outbound response returns without dequeue`() {
        service.lastSentMessage.set("<U>setTemp 20</U=ab>")
        service.canQueue.enqueue(CanMessage(id = 7, data = "setTemp 20"))

        runParse("<request>setTemp 99</request>")

        assertEquals(1, service.canQueue.size())
        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
    }

    @Test
    fun `CAN2 in use with pending outbound message sets canBusy and returns`() {
        service.lastSentMessage.set("<U>setTemp 20</U=ab>")

        runParse("CAN2 in use")

        assertTrue(service.pollQueue.isCanBusy())
        assertEquals(UartState.Disconnected, service.stateMachine.getCurrentState())
    }

    // ── poll-response match ──

    @Test
    fun `matching poll response updates cache and advances poll`() {
        service.pollQueue.initialize(isMyAir5 = true)
        service.pollQueue.advanceToNext()

        val payload = "<request>getClock</request>".toByteArray(Charsets.UTF_8)
        runParse(String(payload, Charsets.UTF_8))

        assertArrayEquals(payload, service.dataCache.get("getClock"))
        assertEquals(2, service.pollQueue.getIndex())
    }

    @Test
    fun `mismatched poll response does not update cache`() {
        service.pollQueue.initialize(isMyAir5 = true)

        runParse("<request>getClock</request>")

        assertNull(service.dataCache.get("getClock"))
        assertEquals(0, service.pollQueue.getIndex())
    }

    // ── getSystemData enrichment ──

    @Test
    fun `getSystemData poll response is enriched and dhcp gateway removed`() {
        service.pollQueue.initialize(isMyAir5 = true)
        val original = "<request>getSystemData</request><type>0</type><AppStore>none</AppStore>" +
            "<dhcp>auto</dhcp><gateway>192.168.1.1</gateway><MyAppRev>1.0</MyAppRev>"
        runParse(original)

        val stored = service.dataCache.get("getSystemData")
        assertNotNull("Expected getSystemData cached", stored)
        val storedString = String(stored!!, Charsets.UTF_8)
        assertTrue("Expected injected type, got: $storedString", storedString.contains("<type>17</type>"))
        assertTrue("Expected injected AppStore, got: $storedString", storedString.contains("<AppStore>MyAir5</AppStore>"))
        assertTrue("Expected injected MyAppRev, got: $storedString", storedString.contains("<MyAppRev>14.150</MyAppRev>"))
        assertFalse("Expected dhcp removed, got: $storedString", storedString.contains("<dhcp>"))
        assertFalse("Expected gateway removed, got: $storedString", storedString.contains("<gateway>"))
        assertEquals(1, service.pollQueue.getIndex())
    }

    @Test
    fun `getSystemData poll response without app tags is not cached`() {
        service.pollQueue.initialize(isMyAir5 = true)

        runParse("<request>getSystemData</request>")

        assertNull(service.dataCache.get("getSystemData"))
        assertEquals(0, service.pollQueue.getIndex())
    }
}
