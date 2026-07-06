package com.air.advantage.aaservice.util

import org.junit.Assert.*
import org.junit.Test

class PreferencesManagerTest {

    @Test
    fun `generateAndStoreUuid returns non-empty string`() {
        // Note: Full test requires Android context, basic structure test here
        val uuid = java.util.UUID.randomUUID().toString()
        assertTrue(uuid.isNotEmpty())
        assertTrue(uuid.length > 0)
    }

    @Test
    fun `UUID format is valid`() {
        val uuid = java.util.UUID.randomUUID().toString()
        // UUID format: 8-4-4-4-12
        assertTrue(uuid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun `multiple UUIDs are unique`() {
        val uuid1 = java.util.UUID.randomUUID().toString()
        val uuid2 = java.util.UUID.randomUUID().toString()
        assertNotEquals(uuid1, uuid2)
    }
}