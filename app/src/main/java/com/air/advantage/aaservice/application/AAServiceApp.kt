package com.air.advantage.aaservice.application

import android.app.Application
import com.air.advantage.aaservice.util.PreferencesManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AAServiceApp : Application() {
    companion object {
        private lateinit var instance: AAServiceApp
        fun get(): AAServiceApp = instance
    }

    @Inject
    lateinit var prefs: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (prefs.uuid.isEmpty()) {
            prefs.generateAndStoreUuid()
        }
    }
}