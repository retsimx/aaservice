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
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.data.mailbox.MyAir5OutboundMailboxMapper
import com.air.advantage.aaservice.data.mailbox.OutboundMailboxAction
import com.air.advantage.aaservice.data.repository.DataCacheRepository
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.data.uart.UsbAccessoryDataSource
import com.air.advantage.aaservice.di.UartServiceEntryPoint
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground host for USB UART and mailbox WS. Transport selection is owned by
 * [TransportRouter]; this service supplies USB callbacks and lifecycle.
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

    @Volatile
    private var transportRouterField: TransportRouter? = null

    /** Exposed for tests / diagnostics once [ensureTransportRouter] has run. */
    internal val transportRouter: TransportRouter
        get() = ensureTransportRouter()

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
    /** Serializes WS outbound sendUpdate/sendResync so acks cannot interleave. */
    private val outboundWsMutex = Mutex()
    internal var uartIoJob: Job? = null
    internal var periodicJob: Job? = null
    private val closeUartIoStarted = AtomicBoolean(false)

    @Volatile private var currentAccessory: UsbAccessory? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    @Volatile private var lastConnectedState: Boolean? = null

    internal var uartDataSource: UartDataSource? = null
        private set

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
        startPeriodicBroadcastIfNeeded()
        ensureDeviceAdmin()?.let { return it }
        handleNullAction(intent)?.let { return it }
        // Mode sync before accessory discovery so WS never opens UsbAccessory.
        // Intent extra transport_mode is log-only; prefs win.
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
     * Applies [PreferencesManager.transportMode] via [TransportRouter.applyMode].
     * Logs Intent [ServiceHelper.EXTRA_TRANSPORT_MODE] when present; prefs always win.
     */
    private fun applyTransportModeFromPrefs(intent: Intent?) {
        val prefs = resolvePreferencesManager()
        val prefsMode = prefs.transportMode
        val extra = intent?.getStringExtra(ServiceHelper.EXTRA_TRANSPORT_MODE)
        if (intent?.action == ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED) {
            Log.i(
                TAG,
                "TRANSPORT_MODE_CHANGED: intent_extra=$extra prefs=$prefsMode (prefs win)"
            )
        } else {
            Log.d(TAG, "applyTransportModeFromPrefs: prefs=$prefsMode")
        }
        ensureTransportRouter().applyMode(prefsMode)
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
            // Always start as Usb so first applyMode(Ws) runs connect side effects
            // (same-mode applyMode is a no-op).
            initialMode = TransportMode.Usb,
        )
        transportRouterField = router
        return router
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
        transportRouterField = null
        periodicJob?.cancel()
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
            Log.d(TAG, "requestSinglePoll: WS mode, ignoring UART poll '$tag'")
            return
        }
        Log.d(TAG, "requestSinglePoll: '$tag'")
        dispatchEngine.enqueueDirectMessage(tag)
    }

    fun enqueueUartMessage(message: String) {
        if (isWsMode()) {
            Log.d(TAG, "enqueueUartMessage: WS mode mapping '$message'")
            dispatchOutboundMailboxActions(MyAir5OutboundMailboxMapper.mapMessageToCb(message))
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
        when (val action = MyAir5OutboundMailboxMapper.mapCanTokens(canIds)) {
            OutboundMailboxAction.Resync -> {
                Log.d(TAG, "$label: WS mode reg-06 flush → resync")
                dispatchOutboundMailboxActions(listOf(action))
            }
            else -> Log.d(TAG, "$label: WS mode ignoring CAN tokens '$canIds'")
        }
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
     * WS-mode GET_ALL_DATA: request a mailbox resync (no UART schedule polls).
     * USB path remains in [GetAllDataReceiver].
     */
    fun handleGetAllDataWs() {
        Log.d(TAG, "handleGetAllDataWs: resync_mailbox")
        dispatchOutboundMailboxActions(listOf(MyAir5OutboundMailboxMapper.mapGetAllData()))
    }

    /**
     * Sends mapped mailbox actions on [ioScope], serialized via [outboundWsMutex].
     * Ack `error` / timeout are logged; never treated as success.
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
                        is OutboundMailboxAction.Update -> {
                            try {
                                val ack = client.sendUpdate(action.register, action.payload)
                                if (ack.status != MailboxAckStatus.SUCCESS) {
                                    Log.e(
                                        TAG,
                                        "mailbox_update ack failure register=${action.register} " +
                                            "status=${ack.status} reason=${ack.reason}",
                                    )
                                }
                            } catch (e: MailboxAckTimeoutException) {
                                Log.e(TAG, "mailbox_update ack timeout msg_id=${e.msgId}", e)
                            } catch (e: Exception) {
                                Log.e(TAG, "mailbox_update failed register=${action.register}", e)
                            }
                        }
                        OutboundMailboxAction.Resync -> {
                            try {
                                val ack = client.sendResync()
                                if (ack.status != MailboxAckStatus.SUCCESS) {
                                    Log.e(
                                        TAG,
                                        "resync_mailbox ack failure status=${ack.status} " +
                                            "reason=${ack.reason}",
                                    )
                                }
                            } catch (e: MailboxAckTimeoutException) {
                                Log.e(TAG, "resync_mailbox ack timeout msg_id=${e.msgId}", e)
                            } catch (e: Exception) {
                                Log.e(TAG, "resync_mailbox failed", e)
                            }
                        }
                        OutboundMailboxAction.Ignore -> Unit
                    }
                }
            }
        }
    }

    fun broadcastData(tag: String) {
        if (!deviceOpen.get()) {
            Log.d(TAG, "broadcastData: device not open, skipping '$tag'")
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
        var instance: UartForegroundService? = null
    }
}
