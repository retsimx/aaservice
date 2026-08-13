package com.air.advantage.aaservice.service

import com.air.advantage.aaservice.data.mailbox.FakeMailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxConnectionState
import com.air.advantage.aaservice.data.mailbox.MailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.service.daemon.DaemonLifecycle
import com.air.advantage.aaservice.util.TransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ordering / failure-path unit tests for [ModeSwitchCoordinator].
 *
 * Uses fakes for [DaemonLifecycle], USB, and WS; asserts Magisk sits between
 * tearDown and connect (loopback), and that failures never activate USB.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ModeSwitchCoordinatorTest {
    private lateinit var callOrder: MutableList<String>
    private lateinit var statuses: MutableList<ModeSwitchStatus>
    private lateinit var daemon: FakeDaemonLifecycle
    private lateinit var usb: RecordingUsbTransportController
    private lateinit var wsClient: FakeMailboxWsClient
    private var daemonUrl: String = LOOPBACK_URL

    @Before
    fun setUp() {
        callOrder = mutableListOf()
        statuses = mutableListOf()
        daemon = FakeDaemonLifecycle(callOrder)
        usb = RecordingUsbTransportController(callOrder)
        wsClient = FakeMailboxWsClient()
        daemonUrl = LOOPBACK_URL
    }

    @Test
    fun `usb to ws loopback order is tearDown Magisk start connect Connected`() =
        runTest {
            val coordinator = newCoordinator(scope = this, snapshotTimeoutMs = 10_000)

            val job = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            assertEquals(1, wsClient.connectCalls)
            assertEquals(
                listOf("status:Connecting", "tearDown", "daemon.start", "connect"),
                callOrder.toList(),
            )

            wsClient.emitState(MailboxConnectionState.Connected)
            runCurrent()
            job.join()

            assertEquals(
                listOf(
                    "status:Connecting",
                    "tearDown",
                    "daemon.start",
                    "connect",
                    "status:Connected",
                ),
                callOrder.toList(),
            )
            assertEquals(ModeSwitchStatus.Connected, statuses.last())
            assertEquals(0, usb.activateCalls)
            assertEquals(1, daemon.startCalls)
            assertEquals(0, daemon.stopCalls)
        }

    @Test
    fun `usb to ws remote skips Magisk start`() =
        runTest {
            daemonUrl = REMOTE_URL
            val coordinator = newCoordinator(scope = this, snapshotTimeoutMs = 10_000)

            val job = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            wsClient.emitState(MailboxConnectionState.Connected)
            runCurrent()
            job.join()

            assertEquals(
                listOf(
                    "status:Connecting",
                    "tearDown",
                    "connect",
                    "status:Connected",
                ),
                callOrder.toList(),
            )
            assertEquals(0, daemon.startCalls)
            assertEquals(ModeSwitchStatus.Connected, statuses.last())
        }

    @Test
    fun `ws to usb order is disconnect Magisk stop activate Idle`() =
        runTest {
            val router = newRouter(initialMode = TransportMode.Ws)
            router.connectWs()
            callOrder.clear()

            val coordinator =
                newCoordinator(
                    scope = this,
                    router = router,
                    snapshotTimeoutMs = 10_000,
                )

            val job = coordinator.switchTo(TransportMode.Usb)
            runCurrent()
            job.join()

            assertEquals(
                listOf(
                    "status:Connecting",
                    "disconnect",
                    "daemon.stop",
                    "activate",
                    "status:Idle",
                ),
                callOrder.toList(),
            )
            assertEquals(ModeSwitchStatus.Idle, statuses.last())
            assertEquals(1, daemon.stopCalls)
            assertEquals(0, daemon.startCalls)
            assertEquals(1, usb.activateCalls)
            assertEquals(TransportMode.Usb, router.activeMode)
        }

    @Test
    fun `Magisk start fail yields Error no USB activate no connect`() =
        runTest {
            daemon.startResult = false
            val router = newRouter(initialMode = TransportMode.Usb)
            val coordinator = newCoordinator(scope = this, router = router)

            coordinator.switchTo(TransportMode.Ws).join()
            runCurrent()

            assertEquals(
                listOf("status:Connecting", "tearDown", "daemon.start", "status:Error"),
                callOrder.toList(),
            )
            assertEquals(ModeSwitchStatus.Error, statuses.last())
            assertEquals(0, wsClient.connectCalls)
            assertEquals(0, usb.activateCalls)
            assertFalse(statuses.contains(ModeSwitchStatus.Connected))
            assertEquals(TransportMode.Ws, router.activeMode)
        }

    @Test
    fun `Magisk start fail then second switchTo Ws retries Magisk start`() =
        runTest {
            daemon.startResult = false
            val router = newRouter(initialMode = TransportMode.Usb)
            val coordinator = newCoordinator(scope = this, router = router)

            coordinator.switchTo(TransportMode.Ws).join()
            runCurrent()
            assertEquals(ModeSwitchStatus.Error, statuses.last())
            assertEquals(TransportMode.Ws, router.activeMode)
            assertEquals(1, daemon.startCalls)
            assertTrue(coordinator.needsSwitch(TransportMode.Ws))

            callOrder.clear()
            daemon.startResult = true
            val job = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            assertEquals(2, daemon.startCalls)
            assertEquals(1, wsClient.connectCalls)

            wsClient.emitState(MailboxConnectionState.Connected)
            runCurrent()
            job.join()

            assertEquals(ModeSwitchStatus.Connected, statuses.last())
            assertEquals(
                listOf(
                    "status:Connecting",
                    "tearDown",
                    "daemon.start",
                    "connect",
                    "status:Connected",
                ),
                callOrder.toList(),
            )
            assertFalse(coordinator.needsSwitch(TransportMode.Ws))
        }

    @Test
    fun `snapshot timeout then second switchTo Ws retries connect`() =
        runTest {
            val timeoutMs = 5_000L
            val router = newRouter(initialMode = TransportMode.Usb)
            val coordinator = newCoordinator(scope = this, router = router, snapshotTimeoutMs = timeoutMs)

            val failJob = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            assertEquals(1, wsClient.connectCalls)
            // All internal retry attempts exhaust -> Error (no operator intervention needed).
            advanceTimeBy(
                timeoutMs * ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS +
                    ModeSwitchCoordinator.WS_RETRY_DELAY_MS *
                    (ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS - 1) + 1_000,
            )
            runCurrent()
            failJob.join()

            assertEquals(ModeSwitchStatus.Error, statuses.last())
            assertEquals(ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS, wsClient.connectCalls)
            assertEquals(TransportMode.Ws, router.activeMode)
            assertTrue(coordinator.needsSwitch(TransportMode.Ws))

            callOrder.clear()
            // Fresh client so retry connect() is observable (factory reads current wsClient).
            wsClient = FakeMailboxWsClient()
            val retryJob = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            assertTrue(callOrder.contains("connect"))

            wsClient.emitState(MailboxConnectionState.Connected)
            runCurrent()
            retryJob.join()

            assertEquals(ModeSwitchStatus.Connected, statuses.last())
            assertEquals(0, usb.activateCalls)
            assertFalse(coordinator.needsSwitch(TransportMode.Ws))
        }

    @Test
    fun `healthy Connected switchTo Ws is no-op no double connect`() =
        runTest {
            val coordinator = newCoordinator(scope = this, snapshotTimeoutMs = 10_000)

            val job = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            wsClient.emitState(MailboxConnectionState.Connected)
            runCurrent()
            job.join()

            assertEquals(ModeSwitchStatus.Connected, statuses.last())
            assertEquals(1, daemon.startCalls)
            assertEquals(1, wsClient.connectCalls)
            assertFalse(coordinator.needsSwitch(TransportMode.Ws))

            callOrder.clear()
            coordinator.switchTo(TransportMode.Ws).join()
            runCurrent()

            assertEquals(emptyList<String>(), callOrder.toList())
            assertEquals(1, daemon.startCalls)
            assertEquals(1, wsClient.connectCalls)
            assertEquals(ModeSwitchStatus.Connected, statuses.last())
        }

    @Test
    fun `healthy Usb Idle switchTo Usb is no-op no re-activate`() =
        runTest {
            val router = newRouter(initialMode = TransportMode.Usb)
            val coordinator = newCoordinator(scope = this, router = router)

            // Establish Idle via an explicit USB switch (e.g. after prior WS).
            router.prepareWs()
            router.connectWs()
            callOrder.clear()
            statuses.clear()
            coordinator.switchTo(TransportMode.Usb).join()
            runCurrent()

            assertEquals(ModeSwitchStatus.Idle, statuses.last())
            assertEquals(1, usb.activateCalls)
            assertFalse(coordinator.needsSwitch(TransportMode.Usb))

            callOrder.clear()
            val activateBefore = usb.activateCalls
            coordinator.switchTo(TransportMode.Usb).join()
            runCurrent()

            assertEquals(emptyList<String>(), callOrder.toList())
            assertEquals(activateBefore, usb.activateCalls)
            assertEquals(ModeSwitchStatus.Idle, coordinator.lastStatus)
        }

    @Test
    fun `snapshot timeout disconnects WS sets Error and does not activate USB`() =
        runTest {
            val timeoutMs = 5_000L
            val coordinator = newCoordinator(scope = this, snapshotTimeoutMs = timeoutMs)

            val job = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            assertEquals(1, wsClient.connectCalls)
            assertEquals(MailboxConnectionState.Connecting, wsClient.connectionState.value)

            // All internal retry attempts exhaust -> Error (no operator intervention needed).
            advanceTimeBy(
                timeoutMs * ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS +
                    ModeSwitchCoordinator.WS_RETRY_DELAY_MS *
                    (ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS - 1) + 1_000,
            )
            runCurrent()
            job.join()

            assertTrue(callOrder.contains("disconnect"))
            assertEquals(ModeSwitchStatus.Error, statuses.last())
            assertEquals(ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS, wsClient.connectCalls)
            assertEquals(0, usb.activateCalls)
            assertFalse(statuses.contains(ModeSwitchStatus.Connected))
            assertTrue(wsClient.disconnectCalls >= 1)
        }

    @Test
    fun `WS disconnect during snapshot wait yields Error with no USB activate`() =
        runTest {
            val timeoutMs = 10_000L
            val router = newRouter(initialMode = TransportMode.Usb)
            val coordinator = newCoordinator(scope = this, router = router, snapshotTimeoutMs = timeoutMs)

            val job = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            assertEquals(1, wsClient.connectCalls)

            wsClient.emitState(MailboxConnectionState.Disconnected)
            runCurrent()
            // All internal retry attempts exhaust -> Error (no operator intervention needed).
            advanceTimeBy(
                timeoutMs * ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS +
                    ModeSwitchCoordinator.WS_RETRY_DELAY_MS *
                    (ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS - 1) + 1_000,
            )
            runCurrent()
            job.join()

            assertTrue(callOrder.contains("disconnect"))
            assertEquals(ModeSwitchStatus.Error, statuses.last())
            assertEquals(0, usb.activateCalls)
            assertFalse(statuses.contains(ModeSwitchStatus.Connected))
            assertEquals(TransportMode.Ws, router.activeMode)
        }

    @Test
    fun `WS Error during snapshot wait yields Error with no USB activate`() =
        runTest {
            val timeoutMs = 10_000L
            val coordinator = newCoordinator(scope = this, snapshotTimeoutMs = timeoutMs)

            val job = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            wsClient.emitState(MailboxConnectionState.Error("boom"))
            runCurrent()
            // All internal retry attempts exhaust -> Error (no operator intervention needed).
            advanceTimeBy(
                timeoutMs * ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS +
                    ModeSwitchCoordinator.WS_RETRY_DELAY_MS *
                    (ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS - 1) + 1_000,
            )
            runCurrent()
            job.join()

            assertEquals(ModeSwitchStatus.Error, statuses.last())
            assertEquals(ModeSwitchCoordinator.WS_CONNECT_ATTEMPTS, wsClient.connectCalls)
            assertEquals(0, usb.activateCalls)
            assertFalse(statuses.contains(ModeSwitchStatus.Connected))
        }

    @Test
    fun `cancelled in-flight switchToWs disconnects WS without USB activate`() =
        runTest {
            val coordinator = newCoordinator(scope = this, snapshotTimeoutMs = 10_000)

            val job = coordinator.switchTo(TransportMode.Ws)
            runCurrent()
            assertEquals(1, wsClient.connectCalls)
            assertEquals(MailboxConnectionState.Connecting, wsClient.connectionState.value)

            job.cancel()
            runCurrent()
            job.join()

            assertTrue(callOrder.contains("disconnect"))
            assertTrue(wsClient.disconnectCalls >= 1)
            assertEquals(0, usb.activateCalls)
            assertFalse(statuses.contains(ModeSwitchStatus.Connected))
            // Connecting remains so same-mode retry / next switch still runs.
            assertTrue(coordinator.needsSwitch(TransportMode.Ws))
        }

    @Test
    fun `ws to usb still activates when Magisk stop fails`() =
        runTest {
            daemon.stopResult = false
            val router = newRouter(initialMode = TransportMode.Ws)
            router.connectWs()
            callOrder.clear()

            val coordinator = newCoordinator(scope = this, router = router)
            coordinator.switchTo(TransportMode.Usb).join()
            runCurrent()

            assertTrue(callOrder.contains("daemon.stop"))
            assertTrue(callOrder.contains("activate"))
            assertEquals(1, usb.activateCalls)
            assertEquals(ModeSwitchStatus.Idle, statuses.last())
        }

    private fun recordingClient(): MailboxWsClient =
        object : MailboxWsClient by wsClient {
            override fun connect() {
                callOrder += "connect"
                wsClient.connect()
            }

            override fun disconnect() {
                callOrder += "disconnect"
                wsClient.disconnect()
            }
        }

    private fun newRouter(initialMode: TransportMode = TransportMode.Usb): TransportRouter {
        val factory = MailboxWsClientFactory { recordingClient() }
        return TransportRouter(
            mailboxWsClientFactory = factory,
            daemonWsUrl = { daemonUrl },
            usbController = usb,
            initialMode = initialMode,
        )
    }

    private fun newCoordinator(
        scope: CoroutineScope,
        router: TransportRouter = newRouter(),
        snapshotTimeoutMs: Long = ModeSwitchCoordinator.DEFAULT_SNAPSHOT_TIMEOUT_MS,
    ): ModeSwitchCoordinator {
        return ModeSwitchCoordinator(
            daemonLifecycle = daemon,
            transportRouter = router,
            daemonWsUrl = { daemonUrl },
            onStatus = { status ->
                statuses += status
                callOrder += "status:$status"
            },
            scope = scope,
            snapshotTimeoutMs = snapshotTimeoutMs,
        )
    }

    private class FakeDaemonLifecycle(
        private val callOrder: MutableList<String>,
    ) : DaemonLifecycle {
        var startResult: Boolean = true
        var stopResult: Boolean = true
        var statusResult: Boolean = true
        var startCalls: Int = 0
            private set
        var stopCalls: Int = 0
            private set

        override fun start(): Boolean {
            startCalls++
            callOrder += "daemon.start"
            return startResult
        }

        override fun stop(): Boolean {
            stopCalls++
            callOrder += "daemon.stop"
            return stopResult
        }

        override fun status(): Boolean = statusResult
    }

    private class RecordingUsbTransportController(
        private val callOrder: MutableList<String>,
    ) : UsbTransportController {
        var tearDownCalls: Int = 0
            private set
        var activateCalls: Int = 0
            private set

        override fun tearDown() {
            tearDownCalls++
            callOrder += "tearDown"
        }

        override fun activate() {
            activateCalls++
            callOrder += "activate"
        }
    }

    companion object {
        private const val LOOPBACK_URL = "ws://127.0.0.1:2026/v1/mailbox-stream"
        private const val REMOTE_URL = "ws://192.168.1.50:2026/v1/mailbox-stream"
    }
}
