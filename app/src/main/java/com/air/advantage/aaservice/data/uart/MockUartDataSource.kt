package com.air.advantage.aaservice.data.uart

import android.hardware.usb.UsbAccessory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MockUartDataSource : UartDataSource {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var connected = false
    private val _readFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    private val readFlow: Flow<ByteArray> = _readFlow.asSharedFlow()

    override val isConnected: Boolean
        get() = connected

    override fun connect(accessory: UsbAccessory): Boolean {
        Log.d(TAG, "Mock connect called")
        connected = true
        Log.d(TAG, "Mock: Config packet sent")
        return true
    }

   override suspend fun write(data: ByteArray): Boolean {
        if (!connected) return false

        val message = String(data, StandardCharsets.UTF_8)
        Log.d(TAG, "Mock write: $message")

        val response = generateResponse(message)
        _readFlow.tryEmit(response)
        return true
    }

    override fun read(): Flow<ByteArray> = readFlow

    override fun disconnect() {
        Log.d(TAG, "Mock disconnect called")
        connected = false
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    private fun generateResponse(message: String): ByteArray {
        return when {
            message.contains("Ping") -> buildPingResponse()
            message.contains("getSystemData") -> buildSystemDataResponse()
            message.contains("getClock") -> buildClockResponse()
            message.contains("getZoneData") -> buildZoneDataResponse(message)
            else -> buildAckResponse()
        }
    }

    private fun buildPingResponse(): ByteArray {
        val pingFrame = "<U>Ping</U=db>".toByteArray(StandardCharsets.UTF_8)
        val ackFrame = "<ack>1</ack>".toByteArray(StandardCharsets.UTF_8)
        val combined = ByteArray(pingFrame.size + ackFrame.size)
        System.arraycopy(pingFrame, 0, combined, 0, pingFrame.size)
        System.arraycopy(ackFrame, 0, combined, pingFrame.size, ackFrame.size)
        Log.d(TAG, "Mock response: Ping + ack")
        return combined
    }

    private fun buildSystemDataResponse(): ByteArray {
        val frame = "<U=01>type=17;AppStore=MyAir5;Model=MyAir5;Version=1.0</U=db>".toByteArray(StandardCharsets.UTF_8)
        val ackFrame = "<ack>1</ack>".toByteArray(StandardCharsets.UTF_8)
        val combined = ByteArray(frame.size + ackFrame.size)
        System.arraycopy(frame, 0, combined, 0, frame.size)
        System.arraycopy(ackFrame, 0, combined, frame.size, ackFrame.size)
        Log.d(TAG, "Mock response: system data")
        return combined
    }

    private fun buildClockResponse(): ByteArray {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timeStr = sdf.format(Date())
        val frame = "<U=02>time=$timeStr</U=db>".toByteArray(StandardCharsets.UTF_8)
        val ackFrame = "<ack>1</ack>".toByteArray(StandardCharsets.UTF_8)
        val combined = ByteArray(frame.size + ackFrame.size)
        System.arraycopy(frame, 0, combined, 0, frame.size)
        System.arraycopy(ackFrame, 0, combined, frame.size, ackFrame.size)
        Log.d(TAG, "Mock response: clock = $timeStr")
        return combined
    }

    private fun buildZoneDataResponse(message: String): ByteArray {
        val zoneMatch = Regex("zone=(\\d+)").find(message)
        val zoneNum = zoneMatch?.groupValues?.get(1) ?: "1"
        val frame = "<U=03>zone=$zoneNum;state=off;temp=21.0;fan=auto</U=db>".toByteArray(StandardCharsets.UTF_8)
        val ackFrame = "<ack>1</ack>".toByteArray(StandardCharsets.UTF_8)
        val combined = ByteArray(frame.size + ackFrame.size)
        System.arraycopy(frame, 0, combined, 0, frame.size)
        System.arraycopy(ackFrame, 0, combined, frame.size, ackFrame.size)
        Log.d(TAG, "Mock response: zone data for zone $zoneNum")
        return combined
    }

    private fun buildAckResponse(): ByteArray {
        val ackFrame = "<ack>1</ack>".toByteArray(StandardCharsets.UTF_8)
        Log.d(TAG, "Mock response: ack (unknown command)")
        return ackFrame
    }

    companion object {
        private const val TAG = "MockUartDataSource"
    }
}
