package com.air.advantage.aaservice.service

import com.air.advantage.aaservice.data.mailbox.FakeMailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.util.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM unit tests for [TransportRouter]. Robolectric is required because
 * [FakeMailboxWsClient] builds default acks with [org.json.JSONObject].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TransportRouterTest {

    private lateinit var wsClient: FakeMailboxWsClient
    private lateinit var usb: FakeUsbTransportController
    private lateinit var router: TransportRouter
    private val createdUrls = mutableListOf<String>()
    private var daemonUrl: String = "ws://127.0.0.1:2026/v1/mailbox-stream"

    @Before
    fun setUp() {
        wsClient = FakeMailboxWsClient()
        usb = FakeUsbTransportController()
        createdUrls.clear()
        daemonUrl = "ws://127.0.0.1:2026/v1/mailbox-stream"
        router = newRouter(initialMode = TransportMode.Usb)
    }

    private fun newRouter(initialMode: TransportMode): TransportRouter {
        val factory = MailboxWsClientFactory { url ->
            createdUrls += url
            wsClient
        }
        return TransportRouter(
            mailboxWsClientFactory = factory,
            daemonWsUrl = { daemonUrl },
            usbController = usb,
            initialMode = initialMode,
        )
    }

    @Test
    fun `activeMode defaults to initialMode`() {
        assertEquals(TransportMode.Usb, router.activeMode)
    }

    @Test
    fun `applyMode Ws tears down USB then connects and does not activate USB`() {
        router.applyMode(TransportMode.Ws)

        assertEquals(TransportMode.Ws, router.activeMode)
        assertEquals(1, usb.tearDownCalls)
        assertEquals(0, usb.activateCalls)
        assertEquals(1, wsClient.connectCalls)
        assertEquals(0, wsClient.disconnectCalls)
        assertEquals(listOf(daemonUrl), createdUrls)
    }

    @Test
    fun `applyMode Usb disconnects WS then activates USB`() {
        router.applyMode(TransportMode.Ws)
        router.applyMode(TransportMode.Usb)

        assertEquals(TransportMode.Usb, router.activeMode)
        assertEquals(1, wsClient.disconnectCalls)
        assertEquals(1, wsClient.connectCalls)
        assertEquals(1, usb.activateCalls)
        assertEquals(1, usb.tearDownCalls)
        assertNull(router.mailboxWsClient)
    }

    @Test
    fun `applyMode same mode is no-op`() {
        router.applyMode(TransportMode.Usb)

        assertEquals(0, usb.tearDownCalls)
        assertEquals(0, usb.activateCalls)
        assertEquals(0, wsClient.connectCalls)
        assertEquals(0, wsClient.disconnectCalls)
        assertEquals(TransportMode.Usb, router.activeMode)

        router.applyMode(TransportMode.Ws)
        router.applyMode(TransportMode.Ws)

        assertEquals(1, usb.tearDownCalls)
        assertEquals(1, wsClient.connectCalls)
        assertEquals(1, createdUrls.size)
        assertEquals(TransportMode.Ws, router.activeMode)
    }

    @Test
    fun `onUsbAction when Ws does not invoke USB open`() {
        router.applyMode(TransportMode.Ws)
        var openCalls = 0

        router.onUsbAction { openCalls++ }

        assertEquals(0, openCalls)
        assertEquals(0, usb.activateCalls)
    }

    @Test
    fun `onUsbAction when Usb invokes action`() {
        var openCalls = 0

        router.onUsbAction { openCalls++ }

        assertEquals(1, openCalls)
    }

    @Test
    fun `applyMode Ws does not invoke USB open via onUsbAction gate`() {
        router.applyMode(TransportMode.Ws)
        var openCalls = 0
        router.onUsbAction { openCalls++ }
        assertEquals(0, openCalls)
        // tearDown is expected; activate/open are not
        assertEquals(1, usb.tearDownCalls)
        assertEquals(0, usb.activateCalls)
    }

    @Test
    fun `usb to ws to usb tears down and reconnects correctly`() {
        router.applyMode(TransportMode.Ws)
        assertEquals(1, usb.tearDownCalls)
        assertEquals(1, wsClient.connectCalls)

        router.applyMode(TransportMode.Usb)
        assertEquals(1, wsClient.disconnectCalls)
        assertEquals(1, usb.activateCalls)
        assertEquals(TransportMode.Usb, router.activeMode)
    }

    @Test
    fun `shutdown disconnects WS and tears down USB`() {
        router.applyMode(TransportMode.Ws)
        router.shutdown()

        assertTrue(wsClient.disconnectCalls >= 1)
        assertTrue(usb.tearDownCalls >= 1)
        assertNull(router.mailboxWsClient)
    }

    @Test
    fun `applyMode Ws uses daemonWsUrl from supplier at connect time`() {
        daemonUrl = "ws://192.168.1.50:2026/v1/mailbox-stream"

        router.applyMode(TransportMode.Ws)

        assertEquals(listOf("ws://192.168.1.50:2026/v1/mailbox-stream"), createdUrls)
        assertEquals(1, wsClient.connectCalls)
    }

    @Test
    fun `changing URL before applyMode Ws affects endpoint used`() {
        daemonUrl = "ws://10.0.0.1:2026/v1/mailbox-stream"
        router.applyMode(TransportMode.Ws)
        router.applyMode(TransportMode.Usb)

        daemonUrl = "ws://10.0.0.2:2026/v1/mailbox-stream"
        router.applyMode(TransportMode.Ws)

        assertEquals(
            listOf(
                "ws://10.0.0.1:2026/v1/mailbox-stream",
                "ws://10.0.0.2:2026/v1/mailbox-stream",
            ),
            createdUrls,
        )
        assertEquals(2, wsClient.connectCalls)
    }

    private class FakeUsbTransportController : UsbTransportController {
        var tearDownCalls: Int = 0
        var activateCalls: Int = 0

        override fun tearDown() {
            tearDownCalls++
        }

        override fun activate() {
            activateCalls++
        }
    }
}
