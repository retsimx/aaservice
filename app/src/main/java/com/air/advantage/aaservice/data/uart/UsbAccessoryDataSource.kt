package com.air.advantage.aaservice.data.uart

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.data.protocol.FrameParser
import com.air.advantage.aaservice.domain.state.UartDispatchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class UsbAccessoryDataSource(
    private val usbManager: UsbManager? = null,
    private val inputStreamFactory: ((ParcelFileDescriptor) -> InputStream)? = null,
    private val outputStreamFactory: ((ParcelFileDescriptor) -> OutputStream)? = null,
    private val engine: UartDispatchEngine? = null,
    /**
     * Invoked on every inbound ping, mirroring reference `ServiceUart$k.d()` calling
     * `q(true)` so the foreground notification flips to "Connected" as soon as the CB
     * is talking — not only after a successful poll payload (which CAN2 can starve).
     */
    private val onPingObserved: (() -> Unit)? = null
) : UartDataSource {

    constructor(context: Context) : this(
        usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: Boolean
        get() = _isConnected.value

    private val _readFlow = MutableSharedFlow<ByteArray>(replay = 0)
    private val readFlow: Flow<ByteArray> = _readFlow.asSharedFlow()

    private var fileInputStream: InputStream? = null
    private var fileOutputStream: OutputStream? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var readJob: Job? = null

    private val buffer = ByteArray(BUFFER_SIZE)
    private var bufferOffset = 0

    private val parser = FrameParser()

    override fun connect(accessory: UsbAccessory): Boolean {
        val manager = usbManager ?: return false

        Log.d(TAG, "connect: opening accessory manufacturer=${accessory.manufacturer} model=${accessory.model}")
        try {
            parcelFileDescriptor = manager.openAccessory(accessory)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "Looks like there is no usb attached")
            return false
        }

        val pfd = parcelFileDescriptor ?: return false

        fileInputStream = inputStreamFactory?.invoke(pfd) ?: FileInputStream(pfd.fileDescriptor)
        fileOutputStream = outputStreamFactory?.invoke(pfd) ?: FileOutputStream(pfd.fileDescriptor)

        if (!sendConfigPacket()) {
            Log.e(TAG, "connect: config packet failed, disconnecting")
            disconnect()
            return false
        }

        _isConnected.value = true
        Log.i(TAG, "connect: connected, starting read loop")
        readJob = scope.launch { readLoop() }
        return true
    }

    /**
     * Connects using the given streams, sending the USB config packet once, then blocks in
     * [readLoop] until the loop terminates (mirroring the reference `ServiceUart$k.run()`, which
     * runs config then the read loop sequentially on the same thread). Returns `false` if the
     * config packet could not be sent.
     */
    suspend fun connectWithStreams(input: InputStream, output: OutputStream): Boolean {
        Log.i(TAG, "connectWithStreams: connecting")
        fileInputStream = input
        fileOutputStream = output
        if (!sendConfigPacket()) {
            Log.e(TAG, "connectWithStreams: config packet failed, disconnecting")
            disconnect()
            return false
        }
        _isConnected.value = true
        Log.d(TAG, "connectWithStreams: connected, entering read loop")
        readLoop()
        Log.i(TAG, "connectWithStreams: read loop exited")
        return true
    }

    override suspend fun write(data: ByteArray): Boolean {
        val outputStream = fileOutputStream ?: return false
        Log.d(TAG, "write: ${data.size} bytes, preview=${previewOf(data)}")
        var offset = 0
        var remaining = data.size
        while (remaining > 0) {
            val chunkSize = if (remaining <= CHUNK_SIZE) remaining else CHUNK_SIZE
            try {
                outputStream.write(data, offset, chunkSize)
                delay(1)
                offset += chunkSize
                remaining -= chunkSize
            } catch (e: IOException) {
                Log.d(TAG, "Error sending packets, close down")
                disconnect()
                return false
            }
        }
        return true
    }

    override fun read(): Flow<ByteArray> = readFlow

    override fun disconnect() {
        Log.d(TAG, "disconnect: tearing down streams")
        _isConnected.value = false
        readJob?.cancel()
        readJob = null

        runCatching { fileInputStream?.close() }
        fileInputStream = null

        runCatching { fileOutputStream?.close() }
        fileOutputStream = null

        runCatching { parcelFileDescriptor?.close() }
        parcelFileDescriptor = null

        bufferOffset = 0
        buffer.fill(0)
    }

    fun destroy() {
        try {
            disconnect()
        } finally {
            scope.cancel()
        }
    }

    /**
     * Sends the USB config packet exactly once before any framed read/write, mirroring the
     * reference `ServiceUart$k` state 1 → 2 transition. On failure the caller aborts connect.
     */
    private fun sendConfigPacket(): Boolean {
        Log.d(TAG, "sendConfigPacket: sending ${CONFIG_PACKET.size} byte config packet")
        return writeBlocking(CONFIG_PACKET)
    }

    private fun writeBlocking(data: ByteArray): Boolean {
        val outputStream = fileOutputStream ?: return false
        var offset = 0
        var remaining = data.size
        while (remaining > 0) {
            val chunkSize = if (remaining <= CHUNK_SIZE) remaining else CHUNK_SIZE
            try {
                outputStream.write(data, offset, chunkSize)
                Thread.sleep(1)
                offset += chunkSize
                remaining -= chunkSize
            } catch (e: IOException) {
                Log.d(TAG, "Error sending config packet, close down")
                return false
            }
        }
        return true
    }

    private suspend fun readLoop() {
        var retryCount = 0
        while (scope.isActive && _isConnected.value) {
            try {
                val inputStream = fileInputStream
                if (inputStream == null) {
                    Log.d(TAG, "Input Stream is null")
                    _isConnected.value = false
                    return
                }
                val readSize = if (bufferOffset + READ_SIZE > buffer.size) {
                    buffer.fill(0)
                    bufferOffset = 0
                    READ_SIZE
                } else {
                    READ_SIZE
                }
                val bytesRead = inputStream.read(buffer, bufferOffset, readSize)
                if (bytesRead > 0) {
                    Log.v(TAG, "readLoop: read $bytesRead bytes, preview=${previewOf(buffer, bufferOffset, bytesRead)}")
                    bufferOffset += bytesRead
                    processBuffer()
                }
                retryCount = 0
            } catch (e: IOException) {
                val cause = e.cause
                if (cause != null && cause.message?.contains("EBADF") == true) {
                    Log.d(TAG, "UART Failed with EBADF")
                    _isConnected.value = false
                    return
                }
                retryCount++
                if (retryCount > MAX_RETRIES) {
                    Log.d(TAG, "UART Failed to read")
                    _isConnected.value = false
                    return
                }
                Log.d(TAG, "readLoop: read error, retry $retryCount/$MAX_RETRIES", e)
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Frames and dispatches the bytes buffered by [readLoop], mirroring the reference
     * `ServiceUart$k.e()`. A ping frame triggers one [UartDispatchEngine.onPing] whose
     * returned frame is written out; leading pings are then stripped. A complete
     * `<U>..</U=xx>` frame is CRC-validated against its footer before
     * [UartDispatchEngine.onFrame], with the result recorded via
     * [UartDispatchEngine.setCrcOk]. Consumed bytes are compacted left via
     * [FrameParser.shiftBuffer].
     */
    private suspend fun processBuffer() {
        while (true) {
            val start = parser.findStartMarker(buffer)
            if (start < 0) return

            val pingEnd = parser.findEndMarker(start, buffer)
            if (pingEnd > 0) {
                Log.v(TAG, "processBuffer: ping frame detected")
                onPingObserved?.invoke()
                val frame = engine?.onPing()
                if (frame != null) {
                    Log.d(TAG, "processBuffer: onPing frame ready, content=${textPreviewOf(frame)}")
                    write(frame)
                }
                while (true) {
                    val headPingEnd = parser.findEndMarker(0, buffer)
                    if (headPingEnd <= 0) break
                    parser.shiftBuffer(headPingEnd, buffer)
                    bufferOffset -= headPingEnd
                }
                continue
            }

            val frameEnd = parser.findFrameEnd(start, buffer)
            if (frameEnd <= 0) return

            if (start != 0) {
                parser.shiftBuffer(start, buffer)
                bufferOffset -= start
                continue
            }

            val payloadStart = start + 3
            val payloadEnd = frameEnd - 7
            val expected = parser.parseHexByte(frameEnd, buffer)
            val actual = CrcCalculator.compute(buffer, payloadStart, payloadEnd)
            val crcOk = expected == actual
            engine?.setCrcOk(crcOk)
            if (crcOk) {
                val payloadLen = payloadEnd - payloadStart
                Log.d(TAG, "processBuffer: frame CRC ok, payload=$payloadLen bytes")
                parser.extractPayload(buffer, payloadStart, payloadEnd)?.let { payload ->
                    if (payloadLen <= 64) {
                        Log.d(TAG, "processBuffer: payload text='${String(payload, Charsets.UTF_8)}'")
                    }
                    engine?.onFrame(payload)
                }
            } else {
                Log.e(TAG, "processBuffer: frame CRC mismatch expected=$expected actual=$actual")
                if (parser.isGetCan(buffer) >= 0) {
                    engine?.armAckCan()
                }
            }
            parser.shiftBuffer(frameEnd, buffer)
            bufferOffset -= frameEnd
        }
    }

    private fun previewOf(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String {
        val previewLength = minOf(length, PREVIEW_BYTES)
        return data.copyOfRange(offset, offset + previewLength).joinToString(" ") { "%02x".format(it) } +
            if (previewLength < length) "..." else ""
    }

    /**
     * Decodes an outbound `<U>{content}</U={crc}>` frame as text for logging, since dispatch
     * frames are ASCII (tag names, CAN ids), making this more useful than a hex dump for
     * identifying poll/direct/CAN TX at a glance.
     */
    private fun textPreviewOf(data: ByteArray): String {
        val text = String(data, Charsets.UTF_8)
        return if (text.length <= PREVIEW_TEXT_CHARS) text else text.take(PREVIEW_TEXT_CHARS) + "..."
    }

    companion object {
        private const val TAG = "AAService2/Usb"
        private const val CHUNK_SIZE = 63
        private const val READ_SIZE = 256
        private const val BUFFER_SIZE = 3072
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 500L
        private const val PREVIEW_BYTES = 32
        private const val PREVIEW_TEXT_CHARS = 64
        private val CONFIG_PACKET = byteArrayOf(0x00, 0xE1.toByte(), 0x00, 0x00, 0x08, 0x01, 0x00, 0x00)
    }
}
