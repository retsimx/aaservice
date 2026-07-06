package com.air.advantage.aaservice.util

object HardwareDetector {

    enum class HardwareType {
        MY_AIR5,
        MY_AIR4,
        EZONE,
        VAMS,
        ZONE10E,
        LEGACY,
        UNKNOWN
    }

    fun detect(): HardwareType = HardwareType.MY_AIR5

    fun typeBytes(): ByteArray = byteArrayOf(0x31, 0x37)

    fun appStoreBytes(): ByteArray = "MyAir5".toByteArray()

    fun supportsSchedulePolling(): Boolean = false

    val isForcedMyAir5: Boolean get() = false
}