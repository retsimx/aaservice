package com.air.advantage.aaservice.util

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.UUID

class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var uuid: String
        get() = prefs.getString("UUID_FOR_WEBLOGGER", "") ?: ""
        set(value) = prefs.edit().putString("UUID_FOR_WEBLOGGER", value).apply()

    var crashCount: Int
        get() = prefs.getInt("crash_count", 0)
        set(value) = prefs.edit().putInt("crash_count", value).apply()

    fun generateAndStoreUuid(): String {
        val newUuid = UUID.randomUUID().toString()
        uuid = newUuid
        return newUuid
    }
}