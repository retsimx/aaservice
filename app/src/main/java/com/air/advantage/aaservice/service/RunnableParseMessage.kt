package com.air.advantage.aaservice.service

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import com.air.advantage.aaservice.data.protocol.FrameParser
import com.air.advantage.aaservice.util.CryptoHelper
import com.air.advantage.aaservice.util.FujitsuDetector
import com.air.advantage.aaservice.util.HardwareDetector

class RunnableParseMessage(
    private val service: UartForegroundService,
    private val payload: ByteArray
) : Runnable {

    override fun run() {
        if (payload.isEmpty()) return

        val parser = FrameParser()

        if (parser.isNack(payload) > 0) {
            if (service.messageSent.get()) {
                if (parser.isUnknown(payload) >= 0) {
                    Log.w(TAG, "CB doesn't support can messages")
                    service.pollQueue.setCanBusy(true)
                    return
                }
                Log.d(TAG, "Warning got a failed ack back sending CAN message")
            } else {
                Log.d(TAG, "Warning got a failed ack back ")
            }
        } else if (parser.isAck(payload) > 0) {
            Log.d(TAG, "Got a successful ack back")
            service.stateMachine.onCanAck()
        }

        if (parser.isGetCan(payload) >= 0) {
            if (payload.size <= 9 || parser.isGetCan(payload) != 0) {
                return
            }
            if (payload[7].toInt() == 48) {
                val retryCount = service.canStateRepository.recordRetry()
                if (retryCount <= 3) {
                    return
                }
            }
            service.canStateRepository.resetRetry()

            val canString = String(payload, Charsets.UTF_8)
            if (!service.lastGetCanString.compareAndSet(service.lastGetCanString.get(), canString)) {
                return
            }

            val isFujitsu = FujitsuDetector.isFujitsuVariant(service)
            val action = if (isFujitsu)
                UartForegroundService.MESSAGE_FROM_CB_SECURE_FUJITSU
            else
                UartForegroundService.MESSAGE_FROM_CB_SECURE
            val secureIntent = Intent(action).apply {
                putExtra(UartForegroundService.GET_DATA_REQUEST, "rawCan")
                putExtra(action, canString)
            }
            val permission = if (isFujitsu)
                UartForegroundService.SECURE_PERMISSION_FUJITSU
            else
                UartForegroundService.SECURE_PERMISSION
            service.sendBroadcast(secureIntent, permission)

            val encrypted = CryptoHelper.encrypt(payload)
            if (encrypted == null || encrypted.isEmpty()) {
                Log.w(TAG, "RunnableParseMessage - Error encrypting rawCan message - encodedMessage is null")
                return
            }
            val noPermissionIntent = Intent(UartForegroundService.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST).apply {
                component = ComponentName(UartForegroundService.ZONE10_PACKAGE, UartForegroundService.ZONE10_NO_PERMISSION_RECEIVER)
                putExtra(UartForegroundService.GET_DATA_REQUEST, "rawCan")
                putExtra(UartForegroundService.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST, encrypted)
            }
            service.sendBroadcast(noPermissionIntent)
            return
        }

        if (parser.isUnknown(payload) >= 0) {
            Log.w(TAG, "Just got reply unknown")
            return
        }

        val payloadString = String(payload, Charsets.UTF_8)

        val lastSent = service.lastSentMessage.get()
        if (lastSent.isNotEmpty()) {
            val expected = stripFrameWrapper(lastSent)
            val requestContent = extractRequest(parser)
            if (expected == requestContent) {
                if (service.canQueue.isEmpty()) {
                    Log.e(TAG, "Trying to remove a message from the queue that doesn't exist. $expected $payloadString")
                } else {
                    service.canQueue.dequeue()
                }
                return
            }
            if (payloadString == "CAN2 in use") {
                service.pollQueue.setCanBusy(true)
                return
            }
            Log.w(TAG, "request and returned value don't match - $expected $payloadString")
            return
        }

        val currentPoll = service.pollQueue.currentPoll() ?: return
        val expected = stripQuery(currentPoll.tag)
        val requestContent = extractRequest(parser)
        if (expected != requestContent) {
            if (payloadString == "CAN2 in use") {
                service.pollQueue.setCanBusy(true)
            } else {
                Log.w(TAG, "poll request and returned value don't match - $expected $payloadString")
            }
            return
        }

        var data: ByteArray = payload
        if (currentPoll.tag == "getSystemData") {
            data = enrichSystemData(parser, data) ?: return
        }
        if (service.dataCache.hasChanged(currentPoll.tag, data)) {
            service.dataCache.put(currentPoll.tag, data)
            service.broadcastData(currentPoll.tag)
        }
        service.stateMachine.onValidResponse(currentPoll.tag)
        service.pollQueue.advanceToNext()
    }

    private fun extractRequest(parser: FrameParser): String {
        return try {
            String(parser.extractTagContent(payload, "request".toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    private fun stripFrameWrapper(frame: String): String {
        var result = frame
        if (result.length > 10 && result.startsWith("<U>") && result.endsWith(">")) {
            result = result.substring(3, result.length - 7)
        }
        return stripQuery(result)
    }

    private fun stripQuery(tag: String): String {
        val questionIndex = tag.indexOf('?')
        return if (questionIndex >= 0) tag.substring(0, questionIndex) else tag
    }

    private fun enrichSystemData(parser: FrameParser, data: ByteArray): ByteArray? {
        var result = data
        try {
            result = parser.replaceTagContent(result, "type".toByteArray(Charsets.UTF_8), HardwareDetector.typeBytes())
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, String(result, Charsets.UTF_8))
        }
        try {
            result = parser.replaceTagContent(result, "AppStore".toByteArray(Charsets.UTF_8), HardwareDetector.appStoreBytes())
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, String(result, Charsets.UTF_8))
        }
        result = parser.removeTag(result, "dhcp".toByteArray(Charsets.UTF_8), "gateway".toByteArray(Charsets.UTF_8))
        return try {
            parser.replaceTagContent(result, "MyAppRev".toByteArray(Charsets.UTF_8), "14.150".toByteArray(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, String(result, Charsets.UTF_8))
            null
        }
    }

    companion object {
        private const val TAG = "RunnableParseMessage"
    }
}
