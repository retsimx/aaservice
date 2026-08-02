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
 */
class TransportRouter(
    private val mailboxWsClientFactory: MailboxWsClientFactory,
    private val daemonWsUrl: () -> String,
    private val usbController: UsbTransportController,
    initialMode: TransportMode = TransportMode.Usb,
) {
    var activeMode: TransportMode = initialMode
        private set

    /** Active WS client after [applyMode] to [TransportMode.Ws]; null when USB / idle. */
    var mailboxWsClient: MailboxWsClient? = null
        private set

    /**
     * Activates [mode], tearing down the inactive path.
     * Same-mode calls are a no-op.
     */
    fun applyMode(mode: TransportMode) {
        if (mode == activeMode) return
        when (mode) {
            TransportMode.Ws -> {
                // Gate USB actions before tear-down so concurrent open paths no-op.
                activeMode = TransportMode.Ws
                usbController.tearDown()
                val client = mailboxWsClientFactory.create(daemonWsUrl())
                mailboxWsClient = client
                client.connect()
            }
            TransportMode.Usb -> {
                mailboxWsClient?.disconnect()
                mailboxWsClient = null
                activeMode = TransportMode.Usb
                usbController.activate()
            }
        }
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
