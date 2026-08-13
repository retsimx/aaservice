package com.air.advantage.aaservice.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

data class CacheUpdate(
    val tag: String,
    val data: ByteArray
)

class DataCacheRepository {
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val updates = MutableSharedFlow<CacheUpdate>(replay = 1)

    fun put(tag: String, data: ByteArray) {
        val previous = cache.put(tag, data)
        if (!Arrays.equals(previous, data)) {
            updates.tryEmit(CacheUpdate(tag, data))
        }
    }

    fun get(tag: String): ByteArray? = cache[tag]

    fun getUpdates(): Flow<CacheUpdate> = updates

    fun getAllKeys(): Set<String> = cache.keys.toSet()

    fun clear() {
        cache.clear()
    }
}