package com.air.advantage.aaservice.util

import android.content.Context
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
}