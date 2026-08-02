package com.air.advantage.aaservice.util

/**
 * Runtime UART transport selection. String values persist in SharedPreferences.
 */
enum class TransportMode(val value: String) {
    Usb("usb"),
    Ws("ws");

    companion object {
        fun fromValue(raw: String?): TransportMode =
            entries.find { it.value == raw } ?: Usb
    }
}
