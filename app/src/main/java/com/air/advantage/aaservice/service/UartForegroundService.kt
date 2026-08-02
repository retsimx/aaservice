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
import com.air.advantage.aaservice.data.repository.DataCacheRepository
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.data.uart.UsbAccessoryDataSource
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
import com.air.advantage.aaservice.util.ServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UartForegroundService : Service() {

    internal val dataCache = DataCacheRepository()
    internal val registeredReceivers = mutableListOf<BroadcastReceiver>()
    internal val deviceOpen = AtomicBoolean(false)
    private val lastRawCan = AtomicReference("")

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
            sink = uartEventSink
        )
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        val securePermission = if (FujitsuDetector.isFujitsuVariant(this))
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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startPeriodicBroadcastIfNeeded()
        ensureDeviceAdmin()?.let { return it }
        handleNullAction(intent)?.let { return it }
        val resolved = discoverAccessoryIfNeeded(intent?.action) ?: return START_STICKY
        return dispatchAction(resolved.second, resolved.first) ?: START_NOT_STICKY
    }

    private fun startPeriodicBroadcastIfNeeded() {
        if (periodicJob == null) {
            periodicJob = ioScope.launch { periodicInfoBroadcast() }
        }
    }

    private fun ensureDeviceAdmin(): Int? {
        if (ServiceHelper.isDeviceAdminActive(this)) return null
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return START_NOT_STICKY
    }

    private fun handleNullAction(intent: Intent?): Int? {
        if (intent?.action != null) return null
        ServiceHelper.scheduleServiceStart(this, ServiceHelper.ACTION_REQUEST_PERMISSION, 2000)
        stopSelf()
        return START_NOT_STICKY
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
            Log.d(TAG, "USB accessory present - checking permission.")
            resolvedAction = ServiceHelper.ACTION_REQUEST_PERMISSION
        }
        return accessory to resolvedAction
    }

    private fun dispatchAction(action: String?, accessory: UsbAccessory): Int? {
        when (action) {
            ServiceHelper.ACTION_OPEN_DEVICE -> {
                val opened = openAccessory(accessory)
                if (!opened) {
                    Log.d(TAG, "Opening accessory - fail")
                    return START_NOT_STICKY
                }
                sendBroadcast(Intent(ServiceHelper.ACTION_ALLOW_HIDING))
                deviceOpen.set(true)
            }

            ServiceHelper.ACTION_CLOSE_DEVICE -> {
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
                sendBroadcast(Intent(ServiceHelper.ACTION_ALLOW_HIDING))
                ServiceHelper.scheduleServiceStart(this, ServiceHelper.ACTION_OPEN_DEVICE, 0)
            } else {
                sendBroadcast(Intent(ServiceHelper.ACTION_BLOCK_HIDING))
                SystemClock.sleep(1000)
                requestUsbPermission()
            }
        }
    }

    private fun requestUsbPermission() {
        val accessory = currentAccessory ?: return
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent("com.air.advantage.USB_PERMISSION"),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(accessory, pendingIntent)
    }

    override fun onDestroy() {
        ServiceHelper.cancelScheduledServiceStart(this, ServiceHelper.ACTION_OPEN_DEVICE)
        closeUartIo()
        periodicJob?.cancel()
        ioScope.cancel()
        registeredReceivers.forEach { unregisterReceiver(it) }
        registeredReceivers.clear()
        instance = null
        super.onDestroy()
    }

    private fun closeUartIo() {
        if (!closeUartIoStarted.compareAndSet(false, true)) return
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

    fun requestSinglePoll(tag: String) {
        dispatchEngine.enqueueDirectMessage(tag)
    }

    fun enqueueUartMessage(message: String) {
        dispatchEngine.enqueueDirectMessage(message)
    }

    private fun parseCanIds(canIds: String): List<Int> {
        return canIds.trim().split("\\s+".toRegex())
            .mapNotNull { it.toIntOrNull() }
    }

    fun enqueueCanIds(canIds: String) {
        dispatchEngine.enqueueCanIds(parseCanIds(canIds))
    }

    fun processCanIds(canIds: String) {
        dispatchEngine.enqueueCanIds(parseCanIds(canIds))
    }

    fun enqueueBroadcastCanIds(canIds: String) {
        dispatchEngine.enqueueBroadcastCanIds(parseCanIds(canIds))
    }

    fun broadcastData(tag: String) {
        if (!deviceOpen.get()) return
        val lookupTag = if (tag.startsWith("getSystemData")) "getSystemData" else tag
        val data = dataCache.get(lookupTag) ?: return

        val cbIntent = Intent("com.air.advantage.MESSAGE_FROM_CB").apply {
            putExtra("com.air.advantage.GET_DATA_REQUEST", tag)
            putExtra("com.air.advantage.MESSAGE_FROM_CB", data)
        }
        sendBroadcast(cbIntent)
    }

    private fun updateLastRawCan(frame: String): Boolean {
        val expected = lastRawCan.get()
        while (!lastRawCan.compareAndSet(expected, frame)) {
            if (lastRawCan.get() !== expected) return false
        }
        return true
    }

    internal fun handleGetCan(frame: String) {
        if (!updateLastRawCan(frame)) return

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
            putExtra("com.air.advantage.GET_DATA_REQUEST", "rawCan")
            putExtra(secureAction, frame)
        }
        sendBroadcast(secureIntent, permission)

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

            val secureIntent = Intent(secureAction).apply {
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
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        inputStream = input
        outputStream = output
        currentPfd = pfd
        val dataSource = UsbAccessoryDataSource(
            inputStreamFactory = { input },
            outputStreamFactory = { output },
            engine = dispatchEngine
        )
        uartDataSource = dataSource
        closeUartIoStarted.set(false)
        uartIoJob = ioScope.launch {
            dataSource.connectWithStreams(input, output)
        }
        uartIoJob?.invokeOnCompletion {
            closeUartIo()
        }
    }

    fun openAccessory(accessory: UsbAccessory): Boolean {
        val manager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false

        if (currentPfd != null) {
            Log.d(TAG, "already working")
            return true
        }

        val pfd = try {
            manager.openAccessory(accessory)
        } catch (e: IllegalArgumentException) {
            null
        }

        if (pfd == null) {
            Log.d(TAG, "Problem creating a parcelFileDescriptor")
            val prefs = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
            val count = prefs.getInt("crash_count", 0) + 1
            if (count > 5) {
                sendBroadcast(Intent(ServiceHelper.ACTION_REBOOT_DEVICE))
            } else {
                prefs.edit().putInt("crash_count", count).apply()
            }
            stopSelf()
            UsbConnectActivity.finishIfShowing()
            return false
        }

        currentPfd = pfd
        startUartIo(pfd)
        return true
    }

    fun showNotification(connected: Boolean) {
        if (lastConnectedState == connected) return
        lastConnectedState = connected

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
        private const val TAG = "UartForegroundService"
        var instance: UartForegroundService? = null
    }
}
