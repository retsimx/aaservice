package com.air.advantage.aaservice.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.data.protocol.FrameParser
import com.air.advantage.aaservice.data.repository.CanStateRepository
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
import com.air.advantage.aaservice.util.FujitsuDetector
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
    internal val canState = CanStateRepository()
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
        "getZoneData?zone=10",
        "getTimers",
        "getSchedules"
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
                val currentPoll = pollQueue.currentPoll()
                if (currentPoll != null) {
                    val frameBytes = currentPoll.frameTag.toByteArray(Charsets.UTF_8)
                    dataSource.write(frameBytes)
                    Log.d(TAG, "Sent poll: ${currentPoll.tag}")
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

    companion object {
        private const val TAG = "UartForegroundService"
        var instance: UartForegroundService? = null
    }
}