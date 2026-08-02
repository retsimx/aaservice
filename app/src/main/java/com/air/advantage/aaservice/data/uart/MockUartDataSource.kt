package com.air.advantage.aaservice.data.uart

import android.hardware.usb.UsbAccessory
import android.util.Log
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.charset.StandardCharsets

/**
 * Simulated USB accessory that speaks the real wire protocol.
 *
 * Every [write] emits the deterministic sequence `[ping frame] + [response frame]`. The ping
 * frame drives the read-loop dispatch (`onPing()` → outbound write), and the response frame is
 * then CRC-validated and handed to the engine via `onFrame()`. Frames use the wire format
 * `<U>{payload}</U={crc}>` where the CRC footer is [CrcCalculator.computeHex] over the payload,
 * so a real [UsbAccessoryDataSource] framing loop can detect and validate them.
 */
class MockUartDataSource : UartDataSource {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var connected = false
    private val _readFlow = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 16)
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
        val pingFrame = PING_FRAME
        val responseFrame = when {
            message.contains("Ping") -> frame(ACK_PAYLOAD)
            message.contains("getSystemData") -> buildSystemDataFrame()
            message.contains("getClock") -> buildClockFrame()
            message.contains("getZoneData") -> buildZoneDataFrame(message)
            else -> frame(ACK_PAYLOAD)
        }
        val combined = pingFrame + responseFrame
        Log.d(TAG, "Mock response: $combined")
        return combined.toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildSystemDataFrame(): String = frame(
        "<request>getSystemData</request>" +
            "<type>00</type>" +
            "<AppStore>x</AppStore>" +
            "<dhcp>192.168.1.1</dhcp>" +
            "<subnet>255.255.255.0</subnet>" +
            "<gateway>192.168.1.254</gateway>" +
            "<MyAppRev>14.148</MyAppRev>"
    )

    private fun buildClockFrame(): String = frame(
        "<request>getClock</request><time>2026-08-02 12:00:00</time>"
    )

    private fun buildZoneDataFrame(message: String): String {
        val zoneMatch = Regex("zone=(\\d+)").find(message)
        val zoneNum = zoneMatch?.groupValues?.get(1) ?: "1"
        return frame(
            "<request>getZoneData</request>" +
                "<zone>$zoneNum</zone>" +
                "<state>off</state>" +
                "<temp>21.0</temp>" +
                "<fan>auto</fan>"
        )
    }

    private fun frame(payload: String): String =
        "<U>$payload</U=${CrcCalculator.computeHex(payload)}>"

    companion object {
        private const val TAG = "AAService2/Mock"
        private const val ACK_PAYLOAD = "<ack>1</ack>"
        const val PING_FRAME: String = "<U>Ping</U=db>"
    }
}
