package com.air.advantage.aaservice.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DataCacheRepositoryTest {

    private lateinit var repository: DataCacheRepository

    @Before
    fun setUp() {
        repository = DataCacheRepository()
    }

    @Test
    fun putStoresDataByTag() {
        val data = byteArrayOf(1, 2, 3)
        repository.put("temp", data)
        assertArrayEquals(data, repository.get("temp"))
    }

    @Test
    fun getRetrievesStoredData() {
        val data = byteArrayOf(10, 20, 30)
        repository.put("humidity", data)
        assertArrayEquals(data, repository.get("humidity"))
    }

    @Test
    fun getReturnsNullForUnknownTag() {
        assertNull(repository.get("nonexistent"))
    }

    @Test
    fun putEmitsViaSharedFlowOnlyWhenDataChanges() = runBlocking {
        val data1 = byteArrayOf(1, 2, 3)
        val data2 = byteArrayOf(4, 5, 6)

        repository.put("temp", data1)
        val first = repository.getUpdates().first()

        repository.put("temp", data2)
        val second = repository.getUpdates().first()

        assertEquals("temp", first.tag)
        assertArrayEquals(data1, first.data)
        assertArrayEquals(data2, second.data)
    }

    @Test
    fun putDoesNotEmitWhenDataIsUnchanged() = runBlocking {
        val data = byteArrayOf(1, 2, 3)
        repository.put("temp", data)
        repository.put("temp", data)

        val first = repository.getUpdates().first()

        assertArrayEquals(data, first.data)
    }

    @Test
    fun getAllKeysReturnsAllStoredKeys() {
        repository.put("temp", byteArrayOf(1))
        repository.put("humidity", byteArrayOf(2))
        repository.put("pressure", byteArrayOf(3))

        val keys = repository.getAllKeys()

        assertEquals(3, keys.size)
        assertTrue(keys.contains("temp"))
        assertTrue(keys.contains("humidity"))
        assertTrue(keys.contains("pressure"))
    }

    @Test
    fun clearRemovesAllEntries() {
        repository.put("temp", byteArrayOf(1))
        repository.put("humidity", byteArrayOf(2))
        repository.clear()

        assertTrue(repository.getAllKeys().isEmpty())
        assertNull(repository.get("temp"))
        assertNull(repository.get("humidity"))
    }

    @Test
    fun putOverwritesExistingTag() {
        val original = byteArrayOf(1, 2, 3)
        val override = byteArrayOf(9, 9, 9)
        repository.put("tag", original)
        repository.put("tag", override)

        assertArrayEquals(override, repository.get("tag"))
    }
}