package com.air.advantage.aaservice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.data.repository.DataCacheRepository
import com.air.advantage.aaservice.data.repository.PollQueueRepository
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.data.uart.UsbAccessoryDataSource
import com.air.advantage.aaservice.domain.state.UartDispatchEngine
import com.air.advantage.aaservice.domain.state.UartEventSink
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
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
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

class UartForegroundService : Service() {

    internal val pollQueue = PollQueueRepository()
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

    override fun onDestroy() {
        deviceOpen.set(false)
        uartIoJob?.cancel()
        ioScope.cancel()
        registeredReceivers.forEach { unregisterReceiver(it) }
        registeredReceivers.clear()
        instance = null
        super.onDestroy()
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

    fun broadcastData(tag: String) {
        if (!deviceOpen.get()) return
        val data = dataCache.get(tag) ?: return

        val cbIntent = Intent("com.air.advantage.MESSAGE_FROM_CB").apply {
            putExtra("com.air.advantage.GET_DATA_REQUEST", tag)
            putExtra("com.air.advantage.MESSAGE_FROM_CB", data)
        }
        sendBroadcast(cbIntent)
    }

    internal fun onAccessoryDetached() {
        deviceOpen.set(false)
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
            val noPermIntent = Intent("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST").apply {
                component = ComponentName(
                    "com.air.advantage.zone10",
                    "com.air.advantage.ReceiverDataUartForNoPermissionBroadcast"
                )
                putExtra("com.air.advantage.GET_DATA_REQUEST", "rawCan")
                putExtra("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST", encrypted)
            }
            sendBroadcast(noPermIntent)
        } else {
            Log.e(TAG, "Error encrypting rawCan message")
        }
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
        val noPermAction = if (isFujitsu)
            "com.air.advantage.MESSAGE_FROM_CB_NO_PERMISSION_FUJITSU"
        else
            "com.air.advantage.MESSAGE_FROM_CB_NO_PERMISSION"

        while (true) {
            delay(5000)

            val versionCode = try {
                val info = packageManager.getPackageInfo(packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toString()
                }
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
            val noPermIntent = Intent(noPermAction).apply {
                putExtra("com.air.advantage.GET_DATA_REQUEST", "aaServiceInfo")
                putExtra(noPermAction, String(encrypted ?: ByteArray(0), Charsets.UTF_8))
            }
            sendBroadcast(noPermIntent)
        }
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var uartIoJob: Job? = null

    internal var uartDataSource: UartDataSource? = null
        private set

    fun startUartIo(pfd: ParcelFileDescriptor) {
        val dataSource = UsbAccessoryDataSource(
            inputStreamFactory = { FileInputStream(it.fileDescriptor) },
            outputStreamFactory = { FileOutputStream(it.fileDescriptor) },
            engine = dispatchEngine
        )
        uartDataSource = dataSource
        uartIoJob = ioScope.launch {
            dataSource.connectWithStreams(
                FileInputStream(pfd.fileDescriptor),
                FileOutputStream(pfd.fileDescriptor)
            )
        }
    }

    private val maxRetries = 5
    private var notificationShown = false
    @Volatile private var lastNotificationTitle: String? = null
    @Volatile internal var crashCount: Int = 0

    fun openAccessory(accessory: UsbAccessory): Boolean {
        val manager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false

        if (crashCount > maxRetries) {
            sendBroadcast(Intent(ServiceHelper.ACTION_REBOOT_DEVICE))
            return true
        }

        val pfd = manager.openAccessory(accessory) ?: return false
        deviceOpen.set(true)
        startUartIo(pfd)
        return true
    }

    fun showNotification(connected: Boolean) {
        val title = when {
            RebootNotificationService.rebootRequired.get() -> "Reboot Required"
            connected -> "Connected"
            else -> "Not connected"
        }

        if (title == lastNotificationTitle) return

        lastNotificationTitle = title
        notificationShown = true

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("uart_channel", "UART Service", NotificationManager.IMPORTANCE_LOW)
            )
            nm.notify(1,
                Notification.Builder(this, "uart_channel")
                    .setContentTitle(title)
                    .setSmallIcon(R.drawable.ic_info)
                    .setContentText("")
                    .build())
        } else {
            @Suppress("DEPRECATION")
            nm.notify(1,
                Notification.Builder(this)
                    .setContentTitle(title)
                    .setSmallIcon(R.drawable.ic_info)
                    .setContentText("")
                    .build())
        }
    }

    companion object {
        private const val TAG = "UartForegroundService"
        var instance: UartForegroundService? = null
    }
}
