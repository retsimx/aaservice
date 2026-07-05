package com.air.advantage.aaservice.data.repository

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CanStateRepositoryTest {

    private lateinit var repo: CanStateRepository

    @Before
    fun setUp() {
        repo = CanStateRepository()
    }

    @Test
    fun `initial state needsRetry returns false`() {
        assertFalse(repo.needsRetry())
    }

    @Test
    fun `recordRetry increments count`() {
        repo.recordRetry()
        repo.recordRetry()
        repo.recordRetry()
        assertTrue(repo.needsRetry())
    }

    @Test
    fun `needsRetry returns true after 3 retries`() {
        repo.recordRetry()
        repo.recordRetry()
        repo.recordRetry()
        assertTrue(repo.needsRetry())
    }

    @Test
    fun `needsRetry returns false after 2 retries`() {
        repo.recordRetry()
        repo.recordRetry()
        assertFalse(repo.needsRetry())
    }

    @Test
    fun `resetRetry clears count and needsRetry flag`() {
        repo.recordRetry()
        repo.recordRetry()
        repo.recordRetry()
        assertTrue(repo.needsRetry())
        repo.resetRetry()
        assertFalse(repo.needsRetry())
    }

    @Test
    fun `storePendingCanFrame stores frame string`() {
        repo.storePendingCanFrame("setCAN 1 2 3")
        assertEquals("setCAN 1 2 3", repo.getPendingCanFrame())
    }

    @Test
    fun `getPendingCanFrame returns stored frame`() {
        repo.storePendingCanFrame("setCAN 10 20 30")
        assertEquals("setCAN 10 20 30", repo.getPendingCanFrame())
    }

    @Test
    fun `clearPendingCanFrame clears stored frame`() {
        repo.storePendingCanFrame("setCAN 1 2 3")
        repo.clearPendingCanFrame()
        assertNull(repo.getPendingCanFrame())
    }

    @Test
    fun `getPendingCanFrame returns null when empty`() {
        assertNull(repo.getPendingCanFrame())
    }

    @Test
    fun `setNeedsRetry manually sets flag`() {
        repo.setNeedsRetry(true)
        assertTrue(repo.needsRetry())
        repo.setNeedsRetry(false)
        assertFalse(repo.needsRetry())
    }

    @Test
    fun `multiple recordRetry calls beyond 3 keep needsRetry true`() {
        repo.recordRetry()
        repo.recordRetry()
        repo.recordRetry()
        repo.recordRetry()
        repo.recordRetry()
        assertTrue(repo.needsRetry())
    }
}