package com.air.advantage.aaservice.data.uart

import android.hardware.usb.UsbAccessory
import kotlinx.coroutines.flow.Flow

interface UartDataSource {
    val isConnected: Boolean
    fun connect(accessory: UsbAccessory): Boolean
    suspend fun write(data: ByteArray): Boolean
    fun read(): Flow<ByteArray>
    fun disconnect()
}
