package com.air.advantage.aaservice.service

import com.air.advantage.aaservice.data.mailbox.MailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.util.TransportMode

/**
 * USB tear-down / resume callbacks supplied by the hosting service.
 *
 * Kept as an interface (lambdas in production) so [TransportRouter] unit tests
 * do not need a full [UartForegroundService].
 */
interface UsbTransportController {
    /** Close UART I/O, clear deviceOpen, cancel pending OPEN_DEVICE. */
    fun tearDown()

    /** Resume stock USB discover / permission / OPEN_DEVICE path. */
    fun activate()
}

/**
 * Thin selector between USB accessory UART and mailbox WebSocket.
 *
 * Mode is passed in via [applyMode] (prefs live outside). WS clients are created
 * via [mailboxWsClientFactory] using [daemonWsUrl] at connect time so the endpoint
 * is not stuck on provide-time defaults. No SharedPreferences, Magisk, or silent
 * USB fallback on WS failure.
 *
 * Split steps ([prepareWs], [connectWs], [disconnectWs], [activateUsb]) exist so
 * [ModeSwitchCoordinator] can insert Magisk start between USB tear-down and WS
 * connect, and Magisk stop between WS disconnect and USB activate.
 */
class TransportRouter(
    private val mailboxWsClientFactory: MailboxWsClientFactory,
    private val daemonWsUrl: () -> String,
    private val usbController: UsbTransportController,
    initialMode: TransportMode = TransportMode.Usb,
) {
    var activeMode: TransportMode = initialMode
        private set

    /** Active WS client after [connectWs] / [applyMode] to [TransportMode.Ws]; null when USB / idle. */
    var mailboxWsClient: MailboxWsClient? = null
        private set

    /**
     * Activates [mode], tearing down the inactive path.
     * Same-mode calls are a no-op. Does **not** run Magisk lifecycle —
     * prefer [ModeSwitchCoordinator] for exclusivity sequencing.
     */
    fun applyMode(mode: TransportMode) {
        if (mode == activeMode) return
        when (mode) {
            TransportMode.Ws -> {
                prepareWs()
                connectWs()
            }
            TransportMode.Usb -> {
                disconnectWs()
                activateUsb()
            }
        }
    }

    /**
     * Gates USB and tears down accessory ownership without opening WS.
     * Magisk start (when needed) must run after this and before [connectWs].
     */
    fun prepareWs() {
        activeMode = TransportMode.Ws
        usbController.tearDown()
    }

    /** Creates a mailbox client for the current [daemonWsUrl] and calls [MailboxWsClient.connect]. */
    fun connectWs() {
        mailboxWsClient?.disconnect()
        val client = mailboxWsClientFactory.create(daemonWsUrl())
        mailboxWsClient = client
        client.connect()
    }

    /** Disconnects and clears the active WS client (if any). Does not change [activeMode]. */
    fun disconnectWs() {
        mailboxWsClient?.disconnect()
        mailboxWsClient = null
    }

    /**
     * Marks USB mode and resumes the stock accessory path.
     * Caller must have stopped Magisk before this when leaving WS.
     */
    fun activateUsb() {
        activeMode = TransportMode.Usb
        usbController.activate()
    }

    /**
     * Runs [action] only when [activeMode] is [TransportMode.Usb].
     * When WS is active, USB open/discover must not run.
     */
    fun onUsbAction(action: () -> Unit) {
        if (activeMode != TransportMode.Usb) return
        action()
    }

    /** Service [android.app.Service.onDestroy] teardown for both paths. */
    fun shutdown() {
        mailboxWsClient?.disconnect()
        mailboxWsClient = null
        usbController.tearDown()
    }
}
