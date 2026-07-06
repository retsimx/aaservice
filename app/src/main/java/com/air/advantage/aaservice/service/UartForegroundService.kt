package com.air.advantage.aaservice.service

import android.app.Service
import android.content.BroadcastReceiver
import android.os.IBinder
import android.content.Intent
import android.content.IntentFilter
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.data.repository.PollQueueRepository
import com.air.advantage.aaservice.domain.model.CanMessage
import com.air.advantage.aaservice.domain.state.CanMessageQueue
import com.air.advantage.aaservice.domain.state.UartStateMachine
import com.air.advantage.aaservice.receiver.BroadcastCanToCbNoPermissionReceiver
import com.air.advantage.aaservice.receiver.BroadcastCanToCbReceiver
import com.air.advantage.aaservice.receiver.CanToCbNoPermissionReceiver
import com.air.advantage.aaservice.receiver.CanToCbReceiver
import com.air.advantage.aaservice.receiver.GetAllDataReceiver
import com.air.advantage.aaservice.receiver.GetDataReceiver
import com.air.advantage.aaservice.receiver.MessageToCbReceiver
import com.air.advantage.aaservice.receiver.BackupMessageNoPermissionReceiver
import com.air.advantage.aaservice.receiver.BackupMessageReceiver
import com.air.advantage.aaservice.receiver.UsbPermissionReceiver
import com.air.advantage.aaservice.util.FujitsuDetector

class UartForegroundService : Service() {

    companion object {
        var instance: UartForegroundService? = null
    }

    internal val pollQueue = PollQueueRepository()
    internal val canQueue = CanMessageQueue()
    internal val stateMachine = UartStateMachine()
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
}