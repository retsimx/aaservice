package com.air.advantage.aaservice.service

import android.util.Log
import com.air.advantage.aaservice.data.mailbox.MailboxConnectionState
import com.air.advantage.aaservice.data.mailbox.MailboxWsClient
import com.air.advantage.aaservice.service.daemon.DaemonLifecycle
import com.air.advantage.aaservice.service.daemon.SuDaemonLifecycle
import com.air.advantage.aaservice.service.daemon.isLoopbackDaemonUrl
import com.air.advantage.aaservice.util.TransportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Service-local transport connection status for mode-switch exclusivity.
 *
 * Mirrors A1 UI [com.air.advantage.aaservice.ui.main.TransportConnectionStatus]
 * without depending on the UI layer.
 */
enum class ModeSwitchStatus {
    Idle,
    Connecting,
    Connected,
    Error,
}

/**
 * Owns USB ↔ WS exclusivity sequencing: Magisk gate, snapshot wait, and status
 * callbacks. Does **not** silently fall back to USB on Magisk / snapshot failure.
 *
 * @param daemonLifecycle Magisk `cb-daemon` start/stop port
 * @param transportRouter thin USB/WS I/O (split steps used for Magisk insertion)
 * @param daemonWsUrl supplier for the mailbox endpoint (loopback ⇒ Magisk start)
 * @param onStatus status listener (Idle / Connecting / Connected / Error)
 * @param scope coroutine scope for [switchTo] jobs (injectable for tests)
 * @param snapshotTimeoutMs max wait for [MailboxConnectionState.Connected]
 */
class ModeSwitchCoordinator(
    private val daemonLifecycle: DaemonLifecycle,
    private val transportRouter: TransportRouter,
    private val daemonWsUrl: () -> String,
    private val onStatus: (ModeSwitchStatus) -> Unit,
    private val scope: CoroutineScope,
    private val snapshotTimeoutMs: Long = DEFAULT_SNAPSHOT_TIMEOUT_MS,
) {
    private val mutex = Mutex()
    private var switchJob: Job? = null

    /** Last status published via [emitStatus] (Error / Connecting allow same-mode retry). */
    @Volatile
    var lastStatus: ModeSwitchStatus = ModeSwitchStatus.Idle
        private set

    /**
     * Whether [switchTo] should run Magisk/connect/activate for [mode].
     *
     * Same-mode is a **cheap no-op** only when healthy:
     * - [TransportMode.Usb] + [ModeSwitchStatus.Idle]
     * - [TransportMode.Ws] + [ModeSwitchStatus.Connected] with mailbox actually Connected
     *
     * Same-mode **retries** when status is [ModeSwitchStatus.Error] / [ModeSwitchStatus.Connecting],
     * or when mode is Ws but the mailbox is not Connected (stale Connected / null client).
     * This makes operator `am … --es transport_mode ws` work after Magisk/snapshot failure
     * even though [TransportRouter.activeMode] already reports Ws.
     */
    fun needsSwitch(mode: TransportMode): Boolean {
        if (mode != transportRouter.activeMode) return true
        return when (lastStatus) {
            ModeSwitchStatus.Error,
            ModeSwitchStatus.Connecting,
            -> true
            ModeSwitchStatus.Connected -> {
                if (mode != TransportMode.Ws) return false
                val state = transportRouter.mailboxWsClient?.connectionState?.value
                state !is MailboxConnectionState.Connected
            }
            ModeSwitchStatus.Idle -> {
                // Healthy USB Idle: no-op. Ws+Idle (unexpected) still retries connect.
                mode == TransportMode.Ws
            }
        }
    }

    /** Launches an exclusive switch on [scope]; cancels any in-flight switch. */
    fun switchTo(mode: TransportMode): Job {
        switchJob?.cancel()
        return scope.launch {
            mutex.withLock {
                if (!needsSwitch(mode)) {
                    Log.d(TAG, "switchTo: same mode $mode healthy (status=$lastStatus), no-op")
                    return@withLock
                }
                when (mode) {
                    TransportMode.Ws -> switchToWs()
                    TransportMode.Usb -> switchToUsb()
                }
            }
        }.also { switchJob = it }
    }

    private fun emitStatus(status: ModeSwitchStatus) {
        lastStatus = status
        onStatus(status)
    }

    private suspend fun switchToWs() {
        emitStatus(ModeSwitchStatus.Connecting)
        transportRouter.prepareWs()
        // The daemon's boot retry can leave a zombie process (engine dead) or an
        // unnegotiated link that the first connect attempt binds to and times out
        // on. Never give up after one attempt — retry the whole start/connect/
        // await cycle so the aaservice self-heals into the daemon's boot window.
        for (attempt in 1..WS_CONNECT_ATTEMPTS) {
            if (attempt > 1) {
                Log.w(
                    TAG,
                    "switchToWs: retry $attempt/$WS_CONNECT_ATTEMPTS in ${WS_RETRY_DELAY_MS}ms",
                )
                delay(WS_RETRY_DELAY_MS)
            }
            try {
                if (!startMagiskIfLoopback()) return
                transportRouter.connectWs()
                val client = transportRouter.mailboxWsClient
                if (client == null) {
                    Log.e(
                        TAG,
                        "WS connect produced no client (attempt $attempt/$WS_CONNECT_ATTEMPTS); " +
                            "retry: ${SuDaemonLifecycle.AM_RETRY_TRANSPORT_MODE}",
                    )
                    continue
                }
                if (awaitMailboxConnected(client, attempt)) return
            } catch (e: CancellationException) {
                // Job cancel (not snapshot timeout — that is handled inside awaitMailboxConnected).
                Log.w(TAG, "switchToWs cancelled; disconnecting WS (no USB activate)")
                transportRouter.disconnectWs()
                throw e
            }
        }
        emitStatus(ModeSwitchStatus.Error)
    }

    /** Loopback URL ⇒ Magisk start; remote skips. Failure leaves USB down (no silent fallback). */
    private fun startMagiskIfLoopback(): Boolean {
        val url = daemonWsUrl()
        if (!isLoopbackDaemonUrl(url)) return true
        if (daemonLifecycle.start()) return true
        Log.e(
            TAG,
            "Magisk cb-daemon start failed for loopback URL; USB stays down " +
                "(no silent fallback). Operator: " +
                "${SuDaemonLifecycle.suControl(SuDaemonLifecycle.OP_START)} ; " +
                "${SuDaemonLifecycle.suControl(SuDaemonLifecycle.OP_STATUS)} ; " +
                "retry: ${SuDaemonLifecycle.AM_RETRY_TRANSPORT_MODE}",
        )
        emitStatus(ModeSwitchStatus.Error)
        return false
    }

    /** Returns `true` when Connected; on failure disconnects and returns `false` (caller retries). */
    private suspend fun awaitMailboxConnected(
        client: MailboxWsClient,
        attempt: Int,
    ): Boolean {
        val ready =
            try {
                withTimeout(snapshotTimeoutMs) {
                    client.connectionState.first { state ->
                        state is MailboxConnectionState.Connected ||
                            state is MailboxConnectionState.Disconnected ||
                            state is MailboxConnectionState.Error
                    }
                }
            } catch (_: TimeoutCancellationException) {
                Log.e(
                    TAG,
                    "Timed out waiting for snapshot (Connected) after ${snapshotTimeoutMs}ms " +
                        "(attempt $attempt/$WS_CONNECT_ATTEMPTS); disconnecting WS, no USB activate. " +
                        "Retry: ${SuDaemonLifecycle.AM_RETRY_TRANSPORT_MODE}",
                )
                transportRouter.disconnectWs()
                return false
            }

        if (ready !is MailboxConnectionState.Connected) {
            Log.e(
                TAG,
                "WS path ended in $ready before Connected (attempt $attempt/$WS_CONNECT_ATTEMPTS); " +
                    "disconnecting, no USB activate (no silent WS→USB fallback). " +
                    "Retry: ${SuDaemonLifecycle.AM_RETRY_TRANSPORT_MODE}",
            )
            transportRouter.disconnectWs()
            return false
        }

        emitStatus(ModeSwitchStatus.Connected)
        return true
    }

    private suspend fun switchToUsb() {
        emitStatus(ModeSwitchStatus.Connecting)
        transportRouter.disconnectWs()

        if (!daemonLifecycle.stop()) {
            Log.w(
                TAG,
                "Magisk cb-daemon stop failed; still activating USB. Operator: " +
                    "${SuDaemonLifecycle.suControl(SuDaemonLifecycle.OP_STOP)} ; " +
                    "${SuDaemonLifecycle.suControl(SuDaemonLifecycle.OP_STATUS)} ; " +
                    "kill leftover holder if needed; retry: ${SuDaemonLifecycle.AM_RETRY_USB_MODE}",
            )
        }

        transportRouter.activateUsb()
        emitStatus(ModeSwitchStatus.Idle)
    }

    companion object {
        private const val TAG = "ModeSwitchCoordinator"
        const val DEFAULT_SNAPSHOT_TIMEOUT_MS: Long = 10_000L

        /** Max attempts to reach WS Connected; covers the daemon's boot-retry window. */
        const val WS_CONNECT_ATTEMPTS: Int = 5

        /** Delay between failed WS connect attempts. */
        const val WS_RETRY_DELAY_MS: Long = 5_000L
    }
}
