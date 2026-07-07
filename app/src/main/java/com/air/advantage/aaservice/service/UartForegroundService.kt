package com.air.advantage.aaservice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.data.protocol.FrameParser
import com.air.advantage.aaservice.data.repository.DataCacheRepository
import com.air.advantage.aaservice.data.repository.PollQueueRepository
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.data.uart.UsbAccessoryDataSource
import com.air.advantage.aaservice.domain.model.CanMessage
import com.air.advantage.aaservice.domain.state.CanMessageQueue
import com.air.advantage.aaservice.domain.state.UartStateMachine
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
import com.air.advantage.aaservice.util.ServiceHelper
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class UartForegroundService : Service() {

    internal val pollQueue = PollQueueRepository()
    internal val canQueue = CanMessageQueue()
    internal val stateMachine = UartStateMachine()
    internal val dataCache = DataCacheRepository()
    internal val registeredReceivers = mutableListOf<BroadcastReceiver>()

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
        uartIoJob?.cancel()
        pollJob?.cancel()
        canJob?.cancel()
        ioScope.cancel()
        registeredReceivers.forEach { unregisterReceiver(it) }
        registeredReceivers.clear()
        instance = null
        super.onDestroy()
    }

    fun requestFullPoll() {
        POLL_TAGS.forEach { tag ->
            requestSinglePoll(tag)
        }
    }

    fun requestSinglePoll(tag: String) {
        val crc = CrcCalculator.computeHex(tag)
        val frame = "<U>$tag</U=$crc>"
        val canMessage = CanMessage(id = 0, data = frame)
        canQueue.enqueue(canMessage)
    }

    fun enqueueUartMessage(message: String) {
        val crc = CrcCalculator.computeHex(message)
        val frame = "<U>$message</U=$crc>"
        val canMessage = CanMessage(id = 0, data = frame)
        canQueue.enqueue(canMessage)
    }

    private fun parseCanIds(canIds: String): List<Int> {
        return canIds.trim().split("\\s+".toRegex())
            .mapNotNull { it.toIntOrNull() }
    }

    fun enqueueCanIds(canIds: String) {
        parseCanIds(canIds).forEach { id ->
            val canMessage = CanMessage(id = id, data = "")
            canQueue.enqueue(canMessage)
        }
    }

    fun processPollResponse(tag: String) {
        val data = if (tag == "getSystemData") {
            val parser = FrameParser()
            var result = "<type>17</type><AppStore>MyAir5</AppStore><dhcp>auto</dhcp><gateway>192.168.1.1</gateway><MyAppRev>14.150</MyAppRev>".toByteArray(Charsets.UTF_8)
            result = parser.replaceTagContent(result, "type".toByteArray(Charsets.UTF_8), "17".toByteArray(Charsets.UTF_8))
            result = parser.replaceTagContent(result, "AppStore".toByteArray(Charsets.UTF_8), "MyAir5".toByteArray(Charsets.UTF_8))
            result = parser.removeTag(result, "dhcp".toByteArray(Charsets.UTF_8), "dhcp".toByteArray(Charsets.UTF_8))
            result = parser.removeTag(result, "gateway".toByteArray(Charsets.UTF_8), "gateway".toByteArray(Charsets.UTF_8))
            result = parser.replaceTagContent(result, "MyAppRev".toByteArray(Charsets.UTF_8), "14.150".toByteArray(Charsets.UTF_8))
            result
        } else {
            tag.toByteArray(Charsets.UTF_8)
        }

        dataCache.put(tag, data)
        broadcastData(tag)
        stateMachine.onValidResponse(tag)
    }

    fun broadcastData(tag: String) {
        val data = dataCache.get(tag) ?: return
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
            putExtra("com.air.advantage.GET_DATA_REQUEST", tag)
            putExtra(secureAction, String(data, Charsets.UTF_8))
        }
        sendBroadcast(secureIntent, permission)

        val cbIntent = Intent("com.air.advantage.MESSAGE_FROM_CB").apply {
            putExtra("com.air.advantage.GET_DATA_REQUEST", tag)
            putExtra("com.air.advantage.MESSAGE_FROM_CB", String(data, Charsets.UTF_8))
        }
        sendBroadcast(cbIntent)
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

    fun processCanIds(canIds: String) {
        stateMachine.onCanQueued(parseCanIds(canIds))
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var uartIoJob: Job? = null
    private var pollJob: Job? = null
    private var canJob: Job? = null

    internal var uartDataSource: UartDataSource? = null
        private set

    fun startUartIo(pfd: ParcelFileDescriptor) {
        val dataSource = UsbAccessoryDataSource(
            inputStreamFactory = { FileInputStream(it.fileDescriptor) },
            outputStreamFactory = { FileOutputStream(it.fileDescriptor) }
        )
        uartDataSource = dataSource
        uartIoJob = ioScope.launch {
            dataSource.connectWithStreams(
                FileInputStream(pfd.fileDescriptor),
                FileOutputStream(pfd.fileDescriptor)
            )
            handleReadStream(dataSource)
        }
    }

    internal suspend fun handleReadStream(dataSource: UartDataSource) {
        dataSource.read().collect { buffer ->
            processIncomingData(buffer)
        }
    }

    internal fun processIncomingData(buffer: ByteArray) {
        val parser = FrameParser()
        val startMarker = parser.findStartMarker(buffer)
        if (startMarker < 0) return

        if (parser.isAck(buffer) >= 0) {
            Log.d(TAG, "Received ACK")
            return
        }

        if (parser.isNack(buffer) >= 0) {
            Log.d(TAG, "Received NACK")
            return
        }

        val frameEnd = parser.findFrameEnd(startMarker, buffer)
        if (frameEnd < 0) return

        val frame = parser.extractPayload(buffer, startMarker, frameEnd) ?: return
        val frameStr = String(frame, Charsets.UTF_8)

        if (parser.isGetCan(frame) >= 0) {
            Log.d(TAG, "Received getCAN")
            return
        }

        if (parser.isUnknown(frame) >= 0) {
            Log.d(TAG, "Received Unknown frame")
            return
        }

        if (frameStr.contains("Ping")) {
            Log.d(TAG, "Received Ping")
            return
        }

        if (frameStr.contains("CAN2 in use")) {
            Log.d(TAG, "Received CAN2 in use")
            return
        }

        dataCache.put("lastFrame", frame)
    }

    fun handlePollCycle(dataSource: UartDataSource) {
        pollJob = ioScope.launch {
            while (true) {
                // Check CAN queue first
                if (!canQueue.isEmpty()) {
                    val canFrame = canQueue.buildCanFrame()
                    if (canFrame.length > 17) {
                        val frameBytes = canFrame.toByteArray(Charsets.UTF_8)
                        dataSource.write(frameBytes)
                        Log.d(TAG, "Sent CAN frame: $canFrame")
                        canQueue.clear()
                        delay(50)
                        continue
                    }
                }

                val currentPoll = pollQueue.currentPoll()
                if (currentPoll != null) {
                    val frameBytes = currentPoll.frameTag.toByteArray(Charsets.UTF_8)
                    dataSource.write(frameBytes)
                    Log.d(TAG, "Sent poll: ${currentPoll.tag}")
                    stateMachine.onSendPoll(currentPoll.tag, frameBytes)
                }

                pollQueue.advanceToNext()
                delay(50)
            }
        }
    }

    fun sendCanMessages(dataSource: UartDataSource) {
        canJob = ioScope.launch {
            val canFrame = canQueue.buildCanFrame()
            if (canFrame.length <= 17) {
                Log.d(TAG, "CAN frame too short, skipping")
                return@launch
            }

            val frameBytes = canFrame.toByteArray(Charsets.UTF_8)
            dataSource.write(frameBytes)
            Log.d(TAG, "Sent CAN frame: $canFrame")
            canQueue.clear()
        }
    }

    private val maxRetries = 5
    private var notificationShown = false
    @Volatile private var lastNotificationTitle: String? = null
    @Volatile internal var crashCount: Int = 0

    fun openAccessory(accessory: UsbAccessory): Boolean {
        val manager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false

        if (crashCount > maxRetries) {
            sendBroadcast(Intent("com.air.advantage.REBOOT_REQUIRED"))
            return true
        }

        val pfd = manager.openAccessory(accessory) ?: return false
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