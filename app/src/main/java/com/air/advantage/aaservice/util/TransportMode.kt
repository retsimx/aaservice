package com.air.advantage.aaservice.util

/**
 * Runtime UART transport selection. String values persist in SharedPreferences.
 */
enum class TransportMode(val value: String) {
    Usb("usb"),
    Ws("ws"),
    ;

    companion object {
        /** Prefs / defaults: unknown or null → [Usb]. */
        fun fromValue(raw: String?): TransportMode = parseOrNull(raw) ?: Usb

        /** Intent extras: unknown or null → null (caller keeps prefs). */
        fun parseOrNull(raw: String?): TransportMode? = entries.find { it.value == raw }
    }
}
