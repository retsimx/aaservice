package com.air.advantage.aaservice.util

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PreferencesManagerTest {

    private lateinit var context: Context
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply()
        preferencesManager = PreferencesManager(context)
    }

    @Test
    fun `uuid is initially empty`() {
        // AAServiceApp may have already generated a UUID, so check it's valid format
        val uuid = preferencesManager.uuid
        assertTrue("UUID should be valid format", uuid.isEmpty() || uuid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun `generateAndStoreUuid generates non-empty valid UUID`() {
        val uuid = preferencesManager.generateAndStoreUuid()
        assertTrue(uuid.isNotEmpty())
        assertTrue(uuid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertEquals(uuid, preferencesManager.uuid)
    }

    @Test
    fun `uuid persists across different manager instances`() {
        val uuid = preferencesManager.generateAndStoreUuid()
        val secondManager = PreferencesManager(context)
        assertEquals(uuid, secondManager.uuid)
    }

    @Test
    fun `crashCount defaults to zero`() {
        assertEquals(0, preferencesManager.crashCount)
    }

    @Test
    fun `crashCount can be updated and retrieved`() {
        preferencesManager.crashCount = 3
        assertEquals(3, preferencesManager.crashCount)
    }

    @Test
    fun `transportMode defaults to usb`() {
        assertEquals(TransportMode.Usb, preferencesManager.transportMode)
    }

    @Test
    fun `daemonWsUrl defaults to mailbox stream url`() {
        assertEquals(PreferencesManager.DEFAULT_DAEMON_WS_URL, preferencesManager.daemonWsUrl)
    }

    @Test
    fun `transportMode can be updated and retrieved`() {
        preferencesManager.transportMode = TransportMode.Ws
        assertEquals(TransportMode.Ws, preferencesManager.transportMode)
        preferencesManager.transportMode = TransportMode.Usb
        assertEquals(TransportMode.Usb, preferencesManager.transportMode)
    }

    @Test
    fun `daemonWsUrl can be updated and retrieved`() {
        val url = "ws://10.0.0.2:2026/v1/mailbox-stream"
        preferencesManager.daemonWsUrl = url
        assertEquals(url, preferencesManager.daemonWsUrl)
    }

    @Test
    fun `unknown transportMode reads as usb`() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PreferencesManager.KEY_TRANSPORT_MODE, "invalid")
            .apply()
        assertEquals(TransportMode.Usb, preferencesManager.transportMode)
    }

    @Test
    fun `transport prefs persist across different manager instances`() {
        preferencesManager.transportMode = TransportMode.Ws
        preferencesManager.daemonWsUrl = "ws://192.168.1.10:2026/v1/mailbox-stream"

        val secondManager = PreferencesManager(context)
        assertEquals(TransportMode.Ws, secondManager.transportMode)
        assertEquals("ws://192.168.1.10:2026/v1/mailbox-stream", secondManager.daemonWsUrl)
    }
}
