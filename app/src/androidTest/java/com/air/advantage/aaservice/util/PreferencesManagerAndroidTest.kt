package com.air.advantage.aaservice.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesManagerAndroidTest {

    @Test
    fun generateAndStoreUuid_returnsNonEmptyString() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PreferencesManager(context)
        val uuid = manager.generateAndStoreUuid()
        assertTrue(uuid.isNotEmpty())
    }

    @Test
    fun uuidFormat_isValid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PreferencesManager(context)
        val uuid = manager.generateAndStoreUuid()
        assertTrue(uuid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun multipleUuids_areUnique() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PreferencesManager(context)
        val uuid1 = manager.generateAndStoreUuid()
        val uuid2 = manager.generateAndStoreUuid()
        assertNotEquals(uuid1, uuid2)
    }

    @Test
    fun uuid_persistsAcrossInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager1 = PreferencesManager(context)
        val uuid1 = manager1.generateAndStoreUuid()

        val manager2 = PreferencesManager(context)
        assertEquals(uuid1, manager2.uuid)
    }

    @Test
    fun crashCount_defaultsToZero() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PreferencesManager(context)
        assertEquals(0, manager.crashCount)
    }

    @Test
    fun crashCount_canBeSetAndRetrieved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PreferencesManager(context)
        manager.crashCount = 5
        assertEquals(5, manager.crashCount)
    }
}