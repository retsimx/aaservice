package com.air.advantage.aaservice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.data.mailbox.MailboxAckStatus
import com.air.advantage.aaservice.data.mailbox.MailboxAckTimeoutException
import com.air.advantage.aaservice.data.mailbox.MailboxConnectionState
import com.air.advantage.aaservice.data.mailbox.MailboxInbound
import com.air.advantage.aaservice.data.mailbox.MailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.data.mailbox.MyAir5OutboundMailboxMapper
import com.air.advantage.aaservice.data.mailbox.OutboundMailboxAction
import com.air.advantage.aaservice.data.mailbox.ReadOutcome
import com.air.advantage.aaservice.data.repository.DataCacheRepository
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.data.uart.UsbAccessoryDataSource
import com.air.advantage.aaservice.di.UartServiceEntryPoint
import com.air.advantage.aaservice.domain.mailbox.MailboxBroadcastMapper
import com.air.advantage.aaservice.domain.mailbox.MailboxRawCanEncoder
import com.air.advantage.aaservice.domain.mailbox.MappedPoll
import com.air.advantage.aaservice.domain.state.UartDispatchEngine
import com.air.advantage.aaservice.domain.state.UartEventSink
import com.air.advantage.aaservice.receiver.AlertDialogReceiver
import com.air.advantage.aaservice.receiver.BackupMessageNoPermissionReceiver
import com.air.advantage.aaservice.receiver.BackupMessageReceiver
import com.air.advantage.aaservice.receiver.BroadcastCanToCbNoPermissionReceiver
import com.air.advantage.aaservice.receiver.BroadcastCanToCbReceiver
import com.air.advantage.aaservice.receiver.CanToCbNoPermissionReceiver
import com.air.advantage.aaservice.receiver.CanToCbReceiver
import com.air.advantage.aaservice.receiver.GetAllDataReceiver
import com.air.advantage.aaservice.receiver.GetDataReceiver
import com.air.advantage.aaservice.receiver.MessageToCbReceiver
import com.air.advantage.aaservice.receiver.UsbPermissionReceiver
import com.air.advantage.aaservice.service.daemon.DaemonLifecycle
import com.air.advantage.aaservice.service.daemon.SuDaemonLifecycle
import com.air.advantage.aaservice.ui.main.MainActivity
import com.air.advantage.aaservice.ui.usb.UsbConnectActivity
import com.air.advantage.aaservice.util.CryptoHelper
import com.air.advantage.aaservice.util.FujitsuDetector
import com.air.advantage.aaservice.util.HardwareDetector
import com.air.advantage.aaservice.util.PreferencesManager
import com.air.advantage.aaservice.util.ServiceHelper
import com.air.advantage.aaservice.util.TransportMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground host for USB UART and mailbox WS. Mode exclusivity is owned by
 * [ModeSwitchCoordinator] (Magisk + snapshot gate); [TransportRouter] stays thin
 * USB/WS I/O. This service supplies USB callbacks and lifecycle.
 *
 * Not `@AndroidEntryPoint` — Robolectric `buildService` tests construct the service
 * without Hilt. Deps resolve via [UartServiceEntryPoint] with a manual fallback.
 */
class UartForegroundService : Service() {

    internal val dataCache = DataCacheRepository()
    internal val registeredReceivers = mutableListOf<BroadcastReceiver>()
    internal val deviceOpen = AtomicBoolean(false)
    private val lastRawCan = AtomicReference("")

    /** Overridable in tests; production fills from Hilt entry point or fallback. */
    internal var preferencesManager: PreferencesManager? = null
    internal var mailboxWsClientFactory: MailboxWsClientFactory? = null

    /**
     * Overridable Magisk lifecycle; production uses [SuDaemonLifecycle].
     * Inject a succeeding fake in Robolectric so loopback WS switches can complete.
     */
    internal var daemonLifecycle: DaemonLifecycle? = null

    /** Overridable snapshot wait for tests (default matches [ModeSwitchCoordinator]). */
    internal var snapshotTimeoutMs: Long = ModeSwitchCoordinator.DEFAULT_SNAPSHOT_TIMEOUT_MS

    @Volatile
    private var transportRouterField: TransportRouter? = null

    @Volatile
    private var modeSwitchCoordinatorField: ModeSwitchCoordinator? = null

    /** Exposed for tests / diagnostics once [ensureTransportRouter] has run. */
    internal val transportRouter: TransportRouter
        get() = ensureTransportRouter()

    /** Exposed for tests once [ensureModeSwitchCoordinator] has run. */
    internal val modeSwitchCoordinator: ModeSwitchCoordinator
        get() = ensureModeSwitchCoordinator()

    private val usbPermissionReceiver = UsbPermissionReceiver()
    private val getDataReceiver = GetDataReceiver()
    private val getAllDataReceiver = GetAllDataReceiver()
    private val messageToCbReceiver = MessageToCbReceiver()
    private val backupMessageReceiver = BackupMessageReceiver()
    private val backupMessageNoPermissionReceiver = BackupMessageNoPermissionReceiver()
    private val broadcastCanToCbReceiver = BroadcastCanToCbReceiver()
    private val broadcastCanToCbNoPermissionReceiver = BroadcastCanToCbNoPermissionReceiver()
    private val canToCbReceiver = CanToCbReceiver()
    private val canToCbNoPermissionReceiver = CanToCbNoPermissionReceiver()

    private val POLL_TAGS = listOf(
        "getSystemData",
        "getClock",
        "getZoneData?zone=1",
        "getZoneData?zone=2",
        "getZoneData?zone=3",
        "getZoneData?zone=4",
        "getZoneData?zone=5",
        "getZoneData?zone=6",
        "getZoneData?zone=7",
        "getZoneData?zone=8",
        "getZoneData?zone=9",
        "getZoneData?zone=10"
    )

    internal val uartEventSink: UartEventSink = object : UartEventSink {
        override fun onPollData(tag: String, payload: ByteArray) {
            Log.d(TAG, "onPollData: tag='$tag' (${payload.size} bytes)")
            dataCache.put(tag, payload)
            broadcastData(tag)
            showNotification(true)
        }

        override fun onRawCan(payload: ByteArray) {
            handleGetCan(String(payload, Charsets.UTF_8))
        }
    }

    internal val dispatchEngine by lazy {
        UartDispatchEngine(
            pollTags = POLL_TAGS,
            typeBytes = HardwareDetector.typeBytes(),
            appStoreBytes = HardwareDetector.appStoreBytes(),
            sink = uartEventSink,
            logger = { message -> Log.d(TAG, "dispatchEngine: $message") }
        )
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Serializes WS outbound sendWrite/sendCommand so acks cannot interleave. */
    private val outboundWsMutex = Mutex()
    internal var uartIoJob: Job? = null
    internal var periodicJob: Job? = null
    private val closeUartIoStarted = AtomicBoolean(false)

    /**
     * A4 (#51): mailbox → `MESSAGE_FROM_CB` collector. Production attaches the
     * [TransportRouter.mailboxWsClient] after WS mode apply; tests attach a fake
     * via [attachMailboxWsClient] without needing Hilt.
     */
    internal var mailboxWsClient: MailboxWsClient? = null
        private set
    private var mailboxCollectionJob: Job? = null
    private var mailboxStatusJob: Job? = null
    private var daemonStatusJob: Job? = null

    /** Dedupes [armTransientErrorAlert]; reset when a connected state arrives. */
    private val transientErrorAlertArmed = AtomicBoolean(false)

    @Volatile private var currentAccessory: UsbAccessory? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    @Volatile private var lastConnectedState: Boolean? = null

    internal var uartDataSource: UartDataSource? = null
        private set

    /**
     * Attaches a [MailboxWsClient] and starts collecting [MailboxWsClient.incoming] for the
     * lifetime of this attachment (design `41-mailbox-to-message-from-cb.md` §6). Each inbound
     * `mailbox_snapshot` / `mailbox_event` is mapped to poll-tag payloads via
     * [MailboxBroadcastMapper], cached, and broadcast the same way the USB [uartEventSink] does.
     *
     * Collected on [Dispatchers.Unconfined]: [MailboxWsClient.incoming] is a hot
     * [kotlinx.coroutines.flow.SharedFlow] with no backpressure concerns here (mapping + a
     * cache put + an Intent broadcast is cheap), so processing inline on whichever thread
     * emits — the real client's OkHttp WebSocket reader thread, or directly on the calling
     * thread in tests via [com.air.advantage.aaservice.data.mailbox.FakeMailboxWsClient] — keeps
     * behavior synchronous and avoids an extra thread hop.
     */
    internal fun attachMailboxWsClient(client: MailboxWsClient) {
        mailboxWsClient = client
        mailboxCollectionJob?.cancel()
        mailboxCollectionJob = ioScope.launch(Dispatchers.Unconfined) {
            client.incoming.collect { inbound -> onMailboxInbound(inbound) }
        }
    }

    /** Maps one mailbox frame to poll-tag broadcasts; safe to call directly from tests. */
    internal fun onMailboxInbound(inbound: MailboxInbound) {
        when (inbound) {
            is MailboxInbound.Snapshot -> {
                // Full register bank as secure rawCan (typed + raw-hex passthrough merged),
                // then the mapped XML poll tags — broadcast per the B-6 AC (D1).
                MailboxRawCanEncoder.encodeGetCan(inbound)?.let { handleGetCan(it) }
                dispatchMappedPolls(
                    MailboxBroadcastMapper.map(inbound, cachedPayload = dataCache::get),
                )
            }
            is MailboxInbound.Event -> {
                MailboxRawCanEncoder.encodeEventToCan(inbound)?.let { handleGetCan(it) }
                dispatchMappedPolls(
                    MailboxBroadcastMapper.map(inbound, cachedPayload = dataCache::get),
                )
            }
            is MailboxInbound.ReadResult -> {
                // Reconciliation rawCan delivery — the daemon already applied the read.
                MailboxRawCanEncoder.encodeReadResultToCan(inbound)?.let { handleGetCan(it) }
                    ?: Log.d(
                        TAG,
                        "onMailboxInbound: read_result not encodable register=${inbound.register}",
                    )
            }
            is MailboxInbound.Status -> Log.d(
                TAG,
                "onMailboxInbound: status state=${inbound.state} " +
                    "(real handling in the daemonStatus collector)",
            )
            is MailboxInbound.Ack -> Log.d(
                TAG,
                "onMailboxInbound: stray ack msg_id=${inbound.msgId} " +
                    "status=${inbound.status} (awaiters correlate by msg_id)",
            )
            is MailboxInbound.Error -> {
                Log.e(
                    TAG,
                    "onMailboxInbound: protocol error message=${inbound.message} " +
                        "reason=${inbound.reason}",
                )
                armTransientErrorAlert()
            }
            is MailboxInbound.Unknown -> Log.d(
                TAG,
                "onMailboxInbound: unknown type=${inbound.type}",
            )
        }
    }

    /** Caches mapped polls and broadcasts them (deduped via [DataCacheRepository.hasChanged]). */
    private fun dispatchMappedPolls(polls: List<MappedPoll>) {
        for (poll in polls) {
            if (!dataCache.hasChanged(poll.tag, poll.payload)) {
                Log.d(TAG, "onMailboxInbound: unchanged '${poll.tag}', skipping broadcast")
                continue
            }
            Log.d(TAG, "onMailboxInbound: tag='${poll.tag}' (${poll.payload.size} bytes)")
            dataCache.put(poll.tag, poll.payload)
            broadcastData(poll.tag)
        }
    }

    /**
     * WS publish gate (design §6): mailbox broadcasts are allowed once the attached client has
     * reached [MailboxConnectionState.Connected] — even if the USB accessory was never opened.
     */
    private fun isMailboxBroadcastReady(): Boolean =
        mailboxWsClient?.connectionState?.value is MailboxConnectionState.Connected

    /**
     * Syncs the inbound collector with [TransportRouter.mailboxWsClient] after mode changes.
     * Detaches when the router has no client (USB mode). Also mirrors
     * [MailboxConnectionState] and the broker [MailboxWsClient.daemonStatus] into
     * [TransportStatusStore] for A1 UI.
     */
    internal fun syncMailboxInboundCollector() {
        val routerClient = transportRouterField?.mailboxWsClient
        if (routerClient == null) {
            mailboxCollectionJob?.cancel()
            mailboxCollectionJob = null
            mailboxStatusJob?.cancel()
            mailboxStatusJob = null
            daemonStatusJob?.cancel()
            daemonStatusJob = null
            mailboxWsClient = null
            return
        }
        if (mailboxWsClient !== routerClient || mailboxCollectionJob?.isActive != true) {
            attachMailboxWsClient(routerClient)
            Log.i(TAG, "syncMailboxInboundCollector: collecting TransportRouter mailbox client")
        }
        val collectorsActive = mailboxStatusJob?.isActive == true && daemonStatusJob?.isActive == true
        if (collectorsActive && mailboxWsClient === routerClient) return
        mailboxStatusJob?.cancel()
        mailboxStatusJob = ioScope.launch {
            var previous: MailboxConnectionState? = null
            // StateFlow already conflates repeated emissions; the previous-state
            // tracking guards the reconnect case (Disconnected → Connected).
            routerClient.connectionState.collect { state ->
                publishMailboxConnectionStatus(state)
                // Reconciliation reads fire once per Connected transition, not per
                // status tick.
                val enteringConnected = state is MailboxConnectionState.Connected &&
                    (previous == null || previous !is MailboxConnectionState.Connected)
                previous = state
                if (enteringConnected) {
                    Log.d(TAG, "connectionState: entered Connected, triggering reconciliation reads")
                    reconcileRegisters()
                }
            }
        }
        daemonStatusJob?.cancel()
        daemonStatusJob = ioScope.launch {
            routerClient.daemonStatus.collect { status -> publishDaemonStatus(status) }
        }
    }

    private fun publishModeSwitchStatus(status: ModeSwitchStatus) {
        TransportStatusStore.publish(status)
    }

    /**
     * Maps broker link state (B-6 D2) to the A1 connection UI. The socket-level
     * [MailboxConnectionState] collector stays untouched — last write wins, races accepted.
     */
    private fun publishDaemonStatus(status: MailboxInbound.Status) {
        when (status.state) {
            "synced" -> {
                transientErrorAlertArmed.set(false)
                TransportStatusStore.publish(ModeSwitchStatus.Connected)
                showNotification(true)
            }
            "link_down" -> {
                TransportStatusStore.publish(ModeSwitchStatus.Error)
                if (!deviceOpen.get()) showNotification(false)
            }
            "resyncing", "negotiating" -> {
                TransportStatusStore.publish(ModeSwitchStatus.Connecting)
            }
            else -> Log.w(
                TAG,
                "publishDaemonStatus: unhandled state=${status.state} detail=${status.detail}",
            )
        }
    }

    private fun publishMailboxConnectionStatus(state: MailboxConnectionState) {
        // Idle is owned by ModeSwitchCoordinator (USB path); ignore mailbox Idle so a
        // disconnect mid-switch does not clobber Connecting.
        val mapped = when (state) {
            is MailboxConnectionState.Idle -> return
            is MailboxConnectionState.Connecting -> ModeSwitchStatus.Connecting
            is MailboxConnectionState.Connected -> ModeSwitchStatus.Connected
            is MailboxConnectionState.Disconnected,
            is MailboxConnectionState.Rejected,
            is MailboxConnectionState.Error -> ModeSwitchStatus.Error
        }
        TransportStatusStore.publish(mapped)
        // USB sets connected via onPingObserved; WS has no accessory pings. Without this,
        // showNotification(false) from onStartCommand leaves the "Not connected" alert
        // armed and MyAir5 cold-start stays on the version banner forever.
        when (state) {
            is MailboxConnectionState.Connected -> {
                // A connected state is a success signal: re-arm the next transient error alert.
                transientErrorAlertArmed.set(false)
                showNotification(true)
            }
            is MailboxConnectionState.Disconnected,
            is MailboxConnectionState.Rejected,
            is MailboxConnectionState.Error -> {
                if (!deviceOpen.get()) showNotification(false)
            }
            else -> Unit
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        instance = this

        // USB permission + accessory detach
        registerReceiver(usbPermissionReceiver,
            IntentFilter("com.air.advantage.USB_PERMISSION").apply {
                addAction("android.hardware.usb.action.USB_ACCESSORY_DETACHED")
            })
        registeredReceivers.add(usbPermissionReceiver)

        // Data request receivers
        registerReceiver(getDataReceiver, IntentFilter("com.air.advantage.GET_DATA"))
        registerReceiver(getAllDataReceiver, IntentFilter("com.air.advantage.GET_ALL_DATA"))
        registerReceiver(messageToCbReceiver, IntentFilter("com.air.advantage.MESSAGE_TO_CB"))
        registeredReceivers.add(getDataReceiver)
        registeredReceivers.add(getAllDataReceiver)
        registeredReceivers.add(messageToCbReceiver)

        // Secure broadcast receivers (with permission)
        val isFujitsu = FujitsuDetector.isFujitsuVariant(this)
        val securePermission = if (isFujitsu)
            "com.air.android.secure_comms_fujitsu" else "com.air.android.secure_comms"
        registerReceiver(canToCbReceiver, IntentFilter("com.air.advantage.CAN_TO_CB"), securePermission, null)
        registerReceiver(broadcastCanToCbReceiver, IntentFilter("com.air.advantage.BROADCAST_CAN_TO_CB"), securePermission, null)
        registerReceiver(backupMessageReceiver, IntentFilter("com.air.advantage.BACKUP_MESSAGE"), securePermission, null)
        registeredReceivers.add(canToCbReceiver)
        registeredReceivers.add(broadcastCanToCbReceiver)
        registeredReceivers.add(backupMessageReceiver)

        // No-permission broadcast receivers
        registerReceiver(canToCbNoPermissionReceiver, IntentFilter("com.air.advantage.CAN_TO_CB_NO_PERMISSION"))
        registerReceiver(broadcastCanToCbNoPermissionReceiver, IntentFilter("com.air.advantage.BROADCAST_CAN_TO_CB_NO_PERMISSION"))
        registerReceiver(backupMessageNoPermissionReceiver, IntentFilter("com.air.advantage.BACKUP_MESSAGE_NO_PERMISSION"))
        registeredReceivers.add(canToCbNoPermissionReceiver)
        registeredReceivers.add(broadcastCanToCbNoPermissionReceiver)
        registeredReceivers.add(backupMessageNoPermissionReceiver)

        Log.d(TAG, "onCreate: registered ${registeredReceivers.size} receivers (fujitsu=$isFujitsu, securePermission=$securePermission)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand: action=${intent?.action} flags=$flags startId=$startId")
        // Must promote to foreground before any early-return / stopSelf path —
        // otherwise Android kills us with RemoteServiceException ("AA Service keeps stopping").
        showNotification(deviceOpen.get())
        startPeriodicBroadcastIfNeeded()
        ensureDeviceAdmin()?.let { return it }
        handleNullAction(intent)?.let { return it }
        // Mode sync before accessory discovery so WS never opens UsbAccessory.
        // On TRANSPORT_MODE_CHANGED, valid extras write prefs then switch; else prefs only.
        applyTransportModeFromPrefs(intent)
        if (intent?.action == ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED) {
            return START_STICKY
        }
        if (ensureTransportRouter().activeMode == TransportMode.Ws) {
            Log.d(TAG, "onStartCommand: WS mode active, skipping USB accessory path")
            return START_STICKY
        }
        var result = START_STICKY
        ensureTransportRouter().onUsbAction {
            val resolved = discoverAccessoryIfNeeded(intent?.action) ?: return@onUsbAction
            result = dispatchAction(resolved.second, resolved.first) ?: START_NOT_STICKY
        }
        return result
    }

    private fun startPeriodicBroadcastIfNeeded() {
        if (periodicJob == null) {
            periodicJob = ioScope.launch { periodicInfoBroadcast() }
        }
    }

    private fun ensureDeviceAdmin(): Int? {
        if (ServiceHelper.isDeviceAdminActive(this)) return null
        Log.d(TAG, "ensureDeviceAdmin: not active, launching MainActivity")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return START_NOT_STICKY
    }

    private fun handleNullAction(intent: Intent?): Int? {
        if (intent?.action != null) return null
        Log.d(TAG, "handleNullAction: null action, scheduling permission request and stopping")
        ServiceHelper.scheduleServiceStart(this, ServiceHelper.ACTION_REQUEST_PERMISSION, 2000)
        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Resolves transport mode and applies it via [ModeSwitchCoordinator.switchTo].
     *
     * On [ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED]:
     * - Optional non-blank [ServiceHelper.EXTRA_DAEMON_WS_URL] is persisted first.
     * - If [ServiceHelper.EXTRA_TRANSPORT_MODE] is present and valid (`usb`|`ws`),
     *   it is written to prefs (extra wins), then the coordinator runs that mode.
     * - If the mode extra is absent/invalid, prefs are used unchanged (backward compatible).
     *
     * Other actions: prefs only.
     *
     * Same-mode is **not** blindly skipped: [ModeSwitchCoordinator.switchTo] / [ModeSwitchCoordinator.needsSwitch]
     * still retries Magisk/connect when status is Error (or Ws mailbox not Connected), so operator
     * `am … --es transport_mode ws` after Magisk/snapshot failure works even though
     * [TransportRouter.activeMode] already reports Ws. Healthy Usb+Idle and Ws+Connected remain
     * cheap no-ops inside the coordinator.
     *
     * Joins the switch job before returning so [TransportRouter.activeMode] gates the
     * USB accessory path correctly for the rest of [onStartCommand].
     */
    private fun applyTransportModeFromPrefs(intent: Intent?) {
        val prefs = resolvePreferencesManager()
        if (intent?.action == ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED) {
            val urlExtra = intent.getStringExtra(ServiceHelper.EXTRA_DAEMON_WS_URL)?.trim()
            if (!urlExtra.isNullOrEmpty()) {
                prefs.daemonWsUrl = urlExtra
            }
            val extraRaw = intent.getStringExtra(ServiceHelper.EXTRA_TRANSPORT_MODE)
            val extraMode = TransportMode.parseOrNull(extraRaw)
            if (extraMode != null) {
                prefs.transportMode = extraMode
                Log.i(TAG, "TRANSPORT_MODE_CHANGED: intent_extra=$extraRaw (extra wins)")
            } else {
                Log.i(
                    TAG,
                    "TRANSPORT_MODE_CHANGED: intent_extra=$extraRaw prefs=${prefs.transportMode} (prefs only)",
                )
            }
        } else {
            Log.d(TAG, "applyTransportModeFromPrefs: prefs=${prefs.transportMode}")
        }
        val mode = prefs.transportMode
        ensureTransportRouter()
        val job = ensureModeSwitchCoordinator().switchTo(mode)
        // Bound join so a hung Magisk/su cannot block onStartCommand forever (M1).
        val joined = runBlocking {
            withTimeoutOrNull(MODE_SWITCH_JOIN_TIMEOUT_MS) { job.join() }
        }
        if (joined == null) {
            Log.e(
                TAG,
                "Mode switch join timed out after ${MODE_SWITCH_JOIN_TIMEOUT_MS}ms " +
                    "(mode=$mode); continuing onStartCommand. Retry: " +
                    SuDaemonLifecycle.AM_RETRY_TRANSPORT_MODE,
            )
        }
        syncMailboxInboundCollector()
    }

    /**
     * Lazily builds [TransportRouter] with Hilt deps when available, else manual
     * [PreferencesManager] + OkHttp factory (Robolectric / no-Hilt path).
     */
    internal fun ensureTransportRouter(): TransportRouter {
        transportRouterField?.let { return it }
        resolvePreferencesManager()
        resolveMailboxWsClientFactory()
        val prefs = preferencesManager!!
        val factory = mailboxWsClientFactory!!
        val router = TransportRouter(
            mailboxWsClientFactory = factory,
            daemonWsUrl = { prefs.daemonWsUrl },
            usbController = object : UsbTransportController {
                override fun tearDown() {
                    Log.d(TAG, "UsbTransportController.tearDown")
                    ServiceHelper.cancelScheduledServiceStart(
                        this@UartForegroundService,
                        ServiceHelper.ACTION_OPEN_DEVICE,
                    )
                    closeUartIo()
                }

                override fun activate() {
                    Log.d(TAG, "UsbTransportController.activate: scheduling REQUEST_PERMISSION")
                    ServiceHelper.scheduleServiceStart(
                        this@UartForegroundService,
                        ServiceHelper.ACTION_REQUEST_PERMISSION,
                        0,
                    )
                }
            },
            // Always start as Usb so first switchTo(Ws) runs connect side effects
            // (healthy same-mode is a no-op inside ModeSwitchCoordinator.needsSwitch).
            initialMode = TransportMode.Usb,
        )
        transportRouterField = router
        return router
    }

    /**
     * Lazily builds [ModeSwitchCoordinator] around [ensureTransportRouter] with
     * [SuDaemonLifecycle] (or an injected test fake).
     */
    internal fun ensureModeSwitchCoordinator(): ModeSwitchCoordinator {
        modeSwitchCoordinatorField?.let { return it }
        val router = ensureTransportRouter()
        val prefs = resolvePreferencesManager()
        val daemon = daemonLifecycle ?: SuDaemonLifecycle().also { daemonLifecycle = it }
        val coordinator = ModeSwitchCoordinator(
            daemonLifecycle = daemon,
            transportRouter = router,
            daemonWsUrl = { prefs.daemonWsUrl },
            onStatus = ::publishModeSwitchStatus,
            scope = ioScope,
            snapshotTimeoutMs = snapshotTimeoutMs,
        )
        modeSwitchCoordinatorField = coordinator
        return coordinator
    }

    private fun resolvePreferencesManager(): PreferencesManager {
        preferencesManager?.let { return it }
        val resolved = try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                UartServiceEntryPoint::class.java,
            ).preferencesManager()
        } catch (e: Exception) {
            Log.d(TAG, "resolvePreferencesManager: Hilt unavailable, using manual instance")
            PreferencesManager(this)
        }
        preferencesManager = resolved
        return resolved
    }

    private fun resolveMailboxWsClientFactory(): MailboxWsClientFactory {
        mailboxWsClientFactory?.let { return it }
        val resolved = try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                UartServiceEntryPoint::class.java,
            ).mailboxWsClientFactory()
        } catch (e: Exception) {
            Log.d(TAG, "resolveMailboxWsClientFactory: Hilt unavailable, using OkHttp factory")
            MailboxWsClientFactory.okHttp()
        }
        mailboxWsClientFactory = resolved
        return resolved
    }

    private fun discoverAccessoryIfNeeded(action: String?): Pair<UsbAccessory, String?>? {
        var accessory = currentAccessory
        var resolvedAction = action
        if (accessory == null) {
            showNotification(false)
            accessory = ServiceHelper.getUsbAccessory(this)
            currentAccessory = accessory
            if (accessory == null) {
                Log.d(TAG, "No accessory present.")
                return null
            }
            Log.d(TAG, "USB accessory present - checking permission. manufacturer=${accessory.manufacturer} model=${accessory.model}")
            resolvedAction = ServiceHelper.ACTION_REQUEST_PERMISSION
        }
        return accessory to resolvedAction
    }

    private fun dispatchAction(action: String?, accessory: UsbAccessory): Int? {
        Log.d(TAG, "dispatchAction: action=$action")
        when (action) {
            ServiceHelper.ACTION_OPEN_DEVICE -> {
                val opened = openAccessory(accessory)
                if (!opened) {
                    Log.d(TAG, "Opening accessory - fail")
                    return START_NOT_STICKY
                }
                sendBroadcast(Intent(ServiceHelper.ACTION_ALLOW_HIDING))
                deviceOpen.set(true)
                // Seed the stock MyAir5 register-06 flush token so the first setCAN is not empty
                // if MyAir5 has not yet queued BROADCAST_CAN_TO_CB (avoids CAN2-in-use on open).
                dispatchEngine.enqueueBroadcastCanIds(listOf("0701000000600000000000000"))
                Log.i(TAG, "dispatchAction: accessory opened, deviceOpen=true")
            }

            ServiceHelper.ACTION_CLOSE_DEVICE -> {
                Log.d(TAG, "dispatchAction: closing device, stopping service")
                stopSelf()
                return START_NOT_STICKY
            }

            ServiceHelper.ACTION_REQUEST_PERMISSION -> handlePermissionRequest(accessory)
        }
        return null
    }

    private fun handlePermissionRequest(accessory: UsbAccessory) {
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
        if (!deviceOpen.get() && usbManager != null) {
            if (usbManager.hasPermission(accessory)) {
                Log.d(TAG, "handlePermissionRequest: permission already granted, opening device")
                sendBroadcast(Intent(ServiceHelper.ACTION_ALLOW_HIDING))
                ServiceHelper.scheduleServiceStart(this, ServiceHelper.ACTION_OPEN_DEVICE, 0)
            } else {
                Log.d(TAG, "handlePermissionRequest: no permission, requesting from user")
                sendBroadcast(Intent(ServiceHelper.ACTION_BLOCK_HIDING))
                SystemClock.sleep(1000)
                requestUsbPermission()
            }
        }
    }

    private fun requestUsbPermission() {
        val accessory = currentAccessory ?: return
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        Log.d(TAG, "requestUsbPermission: requesting for accessory model=${accessory.model}")
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent("com.air.advantage.USB_PERMISSION"),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(accessory, pendingIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        val router = transportRouterField
        if (router != null) {
            router.shutdown()
        } else {
            ServiceHelper.cancelScheduledServiceStart(this, ServiceHelper.ACTION_OPEN_DEVICE)
            closeUartIo()
        }
        modeSwitchCoordinatorField = null
        transportRouterField = null
        periodicJob?.cancel()
        mailboxCollectionJob?.cancel()
        mailboxStatusJob?.cancel()
        daemonStatusJob?.cancel()
        TransportStatusStore.reset()
        ioScope.cancel()
        registeredReceivers.forEach { unregisterReceiver(it) }
        registeredReceivers.clear()
        instance = null
        super.onDestroy()
    }

    private fun closeUartIo() {
        if (!closeUartIoStarted.compareAndSet(false, true)) return
        Log.d(TAG, "closeUartIo: tearing down UART streams")
        uartIoJob?.cancel()
        uartIoJob = null
        deviceOpen.set(false)
        showNotification(false)
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        runCatching { currentPfd?.close() }
        inputStream = null
        outputStream = null
        currentPfd = null
        uartDataSource = null
    }

    /** True when [TransportRouter] is in WebSocket mode. */
    fun isWsMode(): Boolean = ensureTransportRouter().activeMode == TransportMode.Ws

    fun requestSinglePoll(tag: String) {
        if (isWsMode()) {
            Log.d(TAG, "WS mode direct poll dropped: $tag")
            return
        }
        Log.d(TAG, "requestSinglePoll: '$tag'")
        dispatchEngine.enqueueDirectMessage(tag)
    }

    fun enqueueUartMessage(message: String) {
        if (isWsMode()) {
            Log.d(TAG, "enqueueUartMessage: WS mode mapping '$message'")
            dispatchOutboundMailboxActions(MyAir5OutboundMailboxMapper.mapMessage(message))
            return
        }
        Log.d(TAG, "enqueueUartMessage: '$message'")
        dispatchEngine.enqueueDirectMessage(message)
    }

    /**
     * Splits a CAN token string the way stock `ServiceUart.i()` does: collapse double
     * spaces, split on single spaces, keep hex blobs such as
     * `0701000000600000000000000` (these are not decimal ints — `toIntOrNull` drops them).
     */
    private fun parseCanTokens(canIds: String): List<String> {
        return canIds.replace("  ", " ").split(" ").filter { it.isNotEmpty() }
    }

    private fun dispatchWsCanTokens(canIds: String, label: String) {
        val actions = MyAir5OutboundMailboxMapper.mapCanTokens(canIds)
        actions.filterIsInstance<OutboundMailboxAction.Ignore>()
            .forEach { Log.d(TAG, "$label: WS mode ignoring CAN token: ${it.reason}") }
        val meaningful = actions.filter { it !is OutboundMailboxAction.Ignore }
        Log.d(TAG, "$label: WS mode mapped ${meaningful.size} action(s) from '$canIds'")
        dispatchOutboundMailboxActions(meaningful)
    }

    fun enqueueCanIds(canIds: String) {
        if (isWsMode()) {
            dispatchWsCanTokens(canIds, "enqueueCanIds")
            return
        }
        val ids = parseCanTokens(canIds)
        Log.d(TAG, "enqueueCanIds: '$canIds' -> $ids")
        dispatchEngine.enqueueCanIds(ids)
    }

    fun processCanIds(canIds: String) {
        if (isWsMode()) {
            dispatchWsCanTokens(canIds, "processCanIds")
            return
        }
        val ids = parseCanTokens(canIds)
        Log.d(TAG, "processCanIds: '$canIds' -> $ids")
        dispatchEngine.enqueueCanIds(ids)
    }

    fun enqueueBroadcastCanIds(canIds: String) {
        if (isWsMode()) {
            dispatchWsCanTokens(canIds, "enqueueBroadcastCanIds")
            return
        }
        val ids = parseCanTokens(canIds)
        Log.d(TAG, "enqueueBroadcastCanIds: '$canIds' -> $ids")
        dispatchEngine.enqueueBroadcastCanIds(ids)
    }

    /**
     * WS-mode GET_ALL_DATA: force secure rawCan + mailbox resync.
     *
     * Do **not** rebroadcast cached `MESSAGE_FROM_CB` poll tags. USB cold-start
     * only delivers `MESSAGE_FROM_CB_SECURE` rawCan + aaServiceInfo;
     * flooding getSystemData/getZoneData XML on WS was the cold-start mismatch.
     * MyAir5 fills `:2025` from rawCan; resync refreshes the dump for a new frame.
     */
    fun handleGetAllDataWs() {
        Log.d(TAG, "handleGetAllDataWs: force rawCan + resync_mailbox (USB cold-start parity)")
        // Force rawCan rebroadcast (MyAir5 may have missed the earlier secure
        // broadcast while starting up). Clear so handleGetCan does not treat it
        // as a duplicate of the still-cached frame.
        val cachedRawCan = lastRawCan.get()
        if (cachedRawCan.isNotEmpty()) {
            lastRawCan.set("")
            handleGetCan(cachedRawCan)
        }
        dispatchOutboundMailboxActions(listOf(MyAir5OutboundMailboxMapper.mapGetAllData()))
        reconcileRegisters()
    }

    /**
     * Reconciliation reads (B-6 D4): one-shot `read` frames for the primary unit's
     * registers 01 / 05 / 08 and 03 × zones 1..10 (no unitType/unitId — the daemon
     * defaults to the primary unit). Fire-and-forget on [ioScope], serialized via
     * [outboundWsMutex]; outcomes are logged and the rawCan delivery happens through
     * the inbound [MailboxInbound.ReadResult] dispatch, never re-encoded here.
     */
    internal fun reconcileRegisters() {
        val client = mailboxWsClient
        if (client == null ||
            client.connectionState.value !is MailboxConnectionState.Connected
        ) {
            Log.d(TAG, "reconcileRegisters: mailbox not Connected, skipping")
            return
        }
        Log.d(TAG, "reconcileRegisters: reading 01/05/08 + 03×zones 1..10")
        ioScope.launch {
            outboundWsMutex.withLock {
                for (register in listOf("01", "05", "08")) {
                    sendReadWithOutcomeLogging(client, register, null)
                }
                for (zone in 1..10) {
                    sendReadWithOutcomeLogging(client, "03", zone)
                }
            }
        }
    }

    /**
     * Sends a one-shot read and logs the outcome. Error acks arm the transient
     * alert (D5); timeouts / transport failures stay Log.e. Shared by the outbound
     * [OutboundMailboxAction.Read] path and [reconcileRegisters] (B-6 D4).
     */
    private suspend fun sendReadWithOutcomeLogging(
        client: MailboxWsClient,
        register: String,
        zone: Int?,
    ) {
        try {
            when (val outcome = client.sendRead(register, zone)) {
                is ReadOutcome.Value -> Unit
                is ReadOutcome.Error -> {
                    Log.e(
                        TAG,
                        "read failure register=$register zone=$zone " +
                            "reason=${outcome.ack.reason}",
                    )
                    armTransientErrorAlert()
                }
            }
        } catch (e: MailboxAckTimeoutException) {
            Log.e(TAG, "read ack timeout register=$register zone=$zone msg_id=${e.msgId}", e)
        } catch (e: Exception) {
            Log.e(TAG, "read failed register=$register zone=$zone", e)
        }
    }

    /**
     * B-6 D5: surfaces protocol errors / failed acks as a transient alert, deduped so
     * repeated error frames do not re-arm the dialog. Cleared when a connected state
     * arrives: the Connected connection-state branch and the `synced` daemon-status
     * branch both reset it, so the next error cycle can re-arm.
     */
    private fun armTransientErrorAlert() {
        if (!transientErrorAlertArmed.compareAndSet(false, true)) {
            Log.d(TAG, "armTransientErrorAlert: already armed, skipping")
            return
        }
        Log.w(TAG, "armTransientErrorAlert: arming transient error alert")
        AlertDialogReceiver().setAlert(this, active = true, delayMs = 60_000)
    }

    /**
     * Sends mapped mailbox actions on [ioScope], serialized via [outboundWsMutex].
     * Ack `error` / timeout are logged (plus a transient alert for error acks);
     * never treated as success.
     */
    internal fun dispatchOutboundMailboxActions(actions: List<OutboundMailboxAction>) {
        val meaningful = actions.filter { it !is OutboundMailboxAction.Ignore }
        if (meaningful.isEmpty()) {
            Log.d(TAG, "dispatchOutboundMailboxActions: nothing to send")
            return
        }
        val client = ensureTransportRouter().mailboxWsClient
        if (client == null ||
            client.connectionState.value !is MailboxConnectionState.Connected
        ) {
            Log.d(TAG, "dispatchOutboundMailboxActions: WS not Connected, dropping")
            return
        }
        ioScope.launch {
            outboundWsMutex.withLock {
                for (action in meaningful) {
                    when (action) {
                        is OutboundMailboxAction.Write -> {
                            try {
                                val ack = client.sendWrite(
                                    action.register,
                                    action.payload,
                                    action.zone,
                                )
                                if (ack.status != MailboxAckStatus.SUCCESS) {
                                    Log.e(
                                        TAG,
                                        "write ack failure register=${action.register} " +
                                            "status=${ack.status} reason=${ack.reason}",
                                    )
                                    armTransientErrorAlert()
                                }
                            } catch (e: MailboxAckTimeoutException) {
                                Log.e(TAG, "write ack timeout msg_id=${e.msgId}", e)
                            } catch (e: Exception) {
                                Log.e(TAG, "write failed register=${action.register}", e)
                            }
                        }
                        is OutboundMailboxAction.Read -> {
                            sendReadWithOutcomeLogging(client, action.register, action.zone)
                        }
                        is OutboundMailboxAction.Command -> {
                            try {
                                val ack = client.sendCommand(action.action)
                                if (ack.status != MailboxAckStatus.SUCCESS) {
                                    Log.e(
                                        TAG,
                                        "command ack failure action=${action.action} " +
                                            "status=${ack.status} reason=${ack.reason}",
                                    )
                                    armTransientErrorAlert()
                                }
                            } catch (e: MailboxAckTimeoutException) {
                                Log.e(TAG, "command ack timeout msg_id=${e.msgId}", e)
                            } catch (e: Exception) {
                                Log.e(TAG, "command failed action=${action.action}", e)
                            }
                        }
                        is OutboundMailboxAction.Ignore -> Unit
                    }
                }
            }
        }
    }

    fun broadcastData(tag: String) {
        if (!deviceOpen.get() && !isMailboxBroadcastReady()) {
            Log.d(TAG, "broadcastData: device not open and mailbox not ready, skipping '$tag'")
            return
        }
        val lookupTag = if (tag.startsWith("getSystemData")) "getSystemData" else tag
        val data = dataCache.get(lookupTag) ?: run {
            Log.d(TAG, "broadcastData: no cached data for '$lookupTag'")
            return
        }

        val cbIntent = Intent("com.air.advantage.MESSAGE_FROM_CB").apply {
            setPackage(MYAIR5_PACKAGE)
            putExtra("com.air.advantage.GET_DATA_REQUEST", tag)
            putExtra("com.air.advantage.MESSAGE_FROM_CB", data)
        }
        sendBroadcast(cbIntent)
        Log.d(TAG, "broadcastData: sent '$tag' (${data.size} bytes)")
    }

    private fun updateLastRawCan(frame: String): Boolean {
        val expected = lastRawCan.get()
        while (!lastRawCan.compareAndSet(expected, frame)) {
            if (lastRawCan.get() !== expected) return false
        }
        return true
    }

    internal fun handleGetCan(frame: String) {
        if (!updateLastRawCan(frame)) {
            Log.v(TAG, "handleGetCan: duplicate raw CAN frame, skipping")
            return
        }
        Log.d(TAG, "handleGetCan: raw CAN frame (${frame.length} chars)")
        if (frame.length > 400) {
            Log.d(TAG, "handleGetCan: RAW CAN CONTENT: $frame")
        }

        val isFujitsu = FujitsuDetector.isFujitsuVariant(this)
        val secureAction = if (isFujitsu)
            "com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU"
        else
            "com.air.advantage.MESSAGE_FROM_CB_SECURE"
        val permission = if (isFujitsu)
            "com.air.android.secure_comms_fujitsu"
        else
            "com.air.android.secure_comms"

        val secureIntent = Intent(secureAction).apply {
            setPackage(MYAIR5_PACKAGE)
            putExtra("com.air.advantage.GET_DATA_REQUEST", "rawCan")
            putExtra(secureAction, frame)
        }
        sendBroadcast(secureIntent, permission)
        Log.d(TAG, "handleGetCan: sent secure '$secureAction' broadcast (fujitsu=$isFujitsu)")

        val encrypted = CryptoHelper.encrypt(frame.toByteArray(Charsets.UTF_8))
        if (encrypted != null && encrypted.isNotEmpty()) {
            sendNoPermissionBroadcast("rawCan", encrypted)
        } else {
            Log.e(TAG, "Error encrypting rawCan message")
        }
    }

    private fun sendNoPermissionBroadcast(request: String, encrypted: ByteArray) {
        val noPermIntent = Intent("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST").apply {
            component = ComponentName(
                "com.air.advantage.zone10",
                "com.air.advantage.ReceiverDataUartForNoPermissionBroadcast"
            )
            putExtra("com.air.advantage.GET_DATA_REQUEST", request)
            putExtra("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST", encrypted)
        }
        sendBroadcast(noPermIntent)
        Log.d(TAG, "sendNoPermissionBroadcast: sent '$request' (${encrypted.size} encrypted bytes)")
    }

    internal suspend fun periodicInfoBroadcast() {
        val isFujitsu = FujitsuDetector.isFujitsuVariant(this)
        val secureAction = if (isFujitsu)
            "com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU"
        else
            "com.air.advantage.MESSAGE_FROM_CB_SECURE"
        val permission = if (isFujitsu)
            "com.air.android.secure_comms_fujitsu"
        else
            "com.air.android.secure_comms"

        while (true) {
            delay(5000)

            val versionCode = try {
                val info = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            } catch (e: Exception) {
                "0"
            }
            val isAdmin = ServiceHelper.isDeviceAdminActive(this)

            val json = """{"name":"$packageName","version":"$versionCode","enabled":$isAdmin}"""
            Log.v(TAG, "periodicInfoBroadcast: tick json=$json")

            val secureIntent = Intent(secureAction).apply {
                setPackage(MYAIR5_PACKAGE)
                putExtra("com.air.advantage.GET_DATA_REQUEST", "aaServiceInfo")
                putExtra(secureAction, json)
            }
            sendBroadcast(secureIntent, permission)

            val encrypted = CryptoHelper.encrypt(json.toByteArray(Charsets.UTF_8))
            if (encrypted != null && encrypted.isNotEmpty()) {
                sendNoPermissionBroadcast("aaServiceInfo", encrypted)
            } else {
                Log.e(TAG, "Error encrypting aaServiceInfo message")
            }
        }
    }

    fun startUartIo(pfd: ParcelFileDescriptor) {
        Log.i(TAG, "startUartIo: starting UART IO loop")
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        inputStream = input
        outputStream = output
        currentPfd = pfd
        val dataSource = UsbAccessoryDataSource(
            inputStreamFactory = { input },
            outputStreamFactory = { output },
            engine = dispatchEngine,
            onPingObserved = { showNotification(true) }
        )
        uartDataSource = dataSource
        closeUartIoStarted.set(false)
        uartIoJob = ioScope.launch {
            dataSource.connectWithStreams(input, output)
        }
        uartIoJob?.invokeOnCompletion { cause ->
            Log.d(TAG, "startUartIo: UART IO job completed (cause=$cause)")
            closeUartIo()
        }
    }

    fun openAccessory(accessory: UsbAccessory): Boolean {
        if (ensureTransportRouter().activeMode != TransportMode.Usb) {
            Log.d(TAG, "openAccessory: skipped, activeMode=${ensureTransportRouter().activeMode}")
            return false
        }
        val manager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false

        if (currentPfd != null) {
            Log.d(TAG, "already working")
            return true
        }

        Log.d(TAG, "openAccessory: opening manufacturer=${accessory.manufacturer} model=${accessory.model}")
        val pfd = try {
            manager.openAccessory(accessory)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "openAccessory: failed to open accessory", e)
            null
        }

        if (pfd == null) {
            Log.d(TAG, "Problem creating a parcelFileDescriptor")
            val prefs = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
            val count = prefs.getInt("crash_count", 0) + 1
            if (count > 5) {
                Log.e(TAG, "openAccessory: crash_count=$count exceeded threshold, requesting reboot")
                sendBroadcast(Intent(ServiceHelper.ACTION_REBOOT_DEVICE))
            } else {
                prefs.edit().putInt("crash_count", count).apply()
            }
            stopSelf()
            UsbConnectActivity.finishIfShowing()
            return false
        }

        Log.i(TAG, "openAccessory: opened successfully")
        currentPfd = pfd
        startUartIo(pfd)
        return true
    }

    fun showNotification(connected: Boolean) {
        if (lastConnectedState == connected) return
        lastConnectedState = connected
        Log.d(TAG, "showNotification: connected=$connected")

        val title = when {
            RebootNotificationService.rebootRequired.get() -> "Reboot required"
            connected -> "Connected to your system"
            else -> "Not connected to your system"
        }

        AlertDialogReceiver().setAlert(this, active = !connected, if (connected) 0 else 60000)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel(getString(R.string.service_name) + " Notification")
            nm.createNotificationChannel(
                NotificationChannel(
                    "notification_channel_1",
                    getString(R.string.service_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.service_name) + " Notification Icon"
                }
            )
            startForeground(1234,
                Notification.Builder(this, "notification_channel_1")
                    .setContentTitle(title)
                    .setSmallIcon(R.drawable.ic_info)
                    .setContentIntent(null)
                    .setWhen(0L)
                    .build())
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
            @Suppress("DEPRECATION")
            startForeground(1234,
                Notification.Builder(this)
                    .setContentTitle(title)
                    .setSmallIcon(R.drawable.ic_info)
                    .setContentIntent(null)
                    .setWhen(0L)
                    .build())
        }
    }

    companion object {
        private const val TAG = "AAService2/Uart"
        private const val MYAIR5_PACKAGE = "com.air.advantage.myair5"
        /**
         * Upper bound for joining a mode-switch job in [applyTransportModeFromPrefs].
         * Covers Magisk [com.air.advantage.aaservice.service.daemon.RuntimeProcessRunner]
         * timeout plus snapshot wait with headroom.
         */
        internal const val MODE_SWITCH_JOIN_TIMEOUT_MS: Long = 30_000L
        var instance: UartForegroundService? = null
    }
}
