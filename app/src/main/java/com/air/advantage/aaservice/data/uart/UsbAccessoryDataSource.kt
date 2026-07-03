package com.air.advantage.aaservice.data.uart

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
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
    private val outputStreamFactory: ((ParcelFileDescriptor) -> OutputStream)? = null
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

    override fun connect(accessory: UsbAccessory): Boolean {
        val manager = usbManager ?: return false

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
            disconnect()
            return false
        }

        _isConnected.value = true
        readJob = scope.launch { readLoop() }
        return true
    }

    fun connectWithStreams(input: InputStream, output: OutputStream): Boolean {
        fileInputStream = input
        fileOutputStream = output
        if (!sendConfigPacket()) {
            disconnect()
            return false
        }
        _isConnected.value = true
        readJob = scope.launch { readLoop() }
        return true
    }

    override suspend fun write(data: ByteArray): Boolean {
        val outputStream = fileOutputStream ?: return false
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

    private fun sendConfigPacket(): Boolean {
        val configPacket = byteArrayOf(0x00, 0xE1.toByte(), 0x00, 0x00, 0x08, 0x01, 0x00, 0x00)
        return writeBlocking(configPacket)
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
                    bufferOffset += bytesRead
                    _readFlow.emit(buffer.copyOf(bufferOffset))
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
                delay(RETRY_DELAY_MS)
            }
        }
    }

    companion object {
        private const val TAG = "UsbAccessoryDataSource"
        private const val CHUNK_SIZE = 63
        private const val READ_SIZE = 256
        private const val BUFFER_SIZE = 3072
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 500L
    }
}
