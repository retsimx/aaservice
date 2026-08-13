package com.air.advantage.aaservice.util

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

class PreferencesManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        var uuid: String
            get() = prefs.getString("UUID_FOR_WEBLOGGER", "") ?: ""
            set(value) = prefs.edit().putString("UUID_FOR_WEBLOGGER", value).apply()

        var crashCount: Int
            get() = prefs.getInt("crash_count", 0)
            set(value) = prefs.edit().putInt("crash_count", value).apply()

        var transportMode: TransportMode
            get() = TransportMode.fromValue(prefs.getString(KEY_TRANSPORT_MODE, TransportMode.Usb.value))
            set(value) = prefs.edit().putString(KEY_TRANSPORT_MODE, value.value).apply()

        var daemonWsUrl: String
            get() {
                val raw = prefs.getString(KEY_DAEMON_WS_URL, DEFAULT_DAEMON_WS_URL)?.trim().orEmpty()
                return raw.ifEmpty { DEFAULT_DAEMON_WS_URL }
            }
            set(value) {
                val trimmed = value.trim()
                prefs.edit()
                    .putString(KEY_DAEMON_WS_URL, trimmed.ifEmpty { DEFAULT_DAEMON_WS_URL })
                    .apply()
            }

        fun generateAndStoreUuid(): String {
            val newUuid = UUID.randomUUID().toString()
            uuid = newUuid
            return newUuid
        }

        companion object {
            const val KEY_TRANSPORT_MODE = "transport_mode"
            const val KEY_DAEMON_WS_URL = "daemon_ws_url"
            const val DEFAULT_DAEMON_WS_URL = "ws://127.0.0.1:2026/v1/mailbox-stream"
        }
    }
