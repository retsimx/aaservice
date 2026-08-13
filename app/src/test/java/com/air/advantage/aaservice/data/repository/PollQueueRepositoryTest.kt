package com.air.advantage.aaservice.data.repository

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PollQueueRepositoryTest {
    private lateinit var repository: PollQueueRepository

    @Before
    fun setUp() {
        repository = PollQueueRepository()
    }

    @Test
    fun `initialize populates 12 entries for MyAir5`() {
        repository.initialize(isMyAir5 = true)
        // 10 zone entries + getSystemData + getClock = 12
        assertEquals(12, repository.queueSize())
    }

    @Test
    fun `initialize resets index to 0`() {
        repository.initialize(isMyAir5 = true)
        assertEquals(0, repository.getIndex())
    }

    @Test
    fun `currentPoll returns first entry after init`() {
        repository.initialize(isMyAir5 = true)
        val entry = repository.currentPoll()
        assertNotNull(entry)
        assertEquals("getSystemData", entry?.tag)
    }

    @Test
    fun `advanceToNext moves to next entry`() {
        repository.initialize(isMyAir5 = true)
        repository.advanceToNext()
        val entry = repository.currentPoll()
        assertEquals("getClock", entry?.tag)
        assertEquals(1, repository.getIndex())
    }

    @Test
    fun `advanceToNext wraps around at end`() {
        repository.initialize(isMyAir5 = true)
        // Advance through all 12 entries (indices 0..11)
        for (i in 1..11) {
            repository.advanceToNext()
        }
        // Now at index 11, advance should wrap to 0
        val wrapped = repository.advanceToNext()
        assertEquals(0, repository.getIndex())
        assertEquals("getSystemData", wrapped?.tag)
    }

    @Test
    fun `currentPoll returns null when polling suspended`() {
        repository.initialize(isMyAir5 = true)
        repository.suspendPolling()
        assertNull(repository.currentPoll())
    }

    @Test
    fun `currentPoll returns null when queue empty`() {
        // Don't initialize — queue is empty
        assertNull(repository.currentPoll())
    }

    @Test
    fun `reset sets index back to 0`() {
        repository.initialize(isMyAir5 = true)
        repository.advanceToNext()
        repository.advanceToNext()
        repository.reset()
        assertEquals(0, repository.getIndex())
        assertEquals("getSystemData", repository.currentPoll()?.tag)
    }

    @Test
    fun `getIndex returns current index`() {
        repository.initialize(isMyAir5 = true)
        assertEquals(0, repository.getIndex())
        repository.advanceToNext()
        assertEquals(1, repository.getIndex())
        repository.advanceToNext()
        assertEquals(2, repository.getIndex())
    }

    @Test
    fun `suspendPolling and resumePolling toggle isPollActive`() {
        repository.initialize(isMyAir5 = true)
        assertTrue(repository.isPollActive())
        repository.suspendPolling()
        assertFalse(repository.isPollActive())
        repository.resumePolling()
        assertTrue(repository.isPollActive())
    }

    @Test
    fun `setCanBusy and isCanBusy toggle canBusy flag`() {
        assertFalse(repository.isCanBusy())
        repository.setCanBusy(true)
        assertTrue(repository.isCanBusy())
        repository.setCanBusy(false)
        assertFalse(repository.isCanBusy())
    }

    @Test
    fun `frame tags contain CRC from CrcCalculator`() {
        repository.initialize(isMyAir5 = true)
        val entry = repository.currentPoll()!!
        val expectedCrc = CrcCalculator.computeHex("getSystemData")
        assertEquals("<U>getSystemData</U=$expectedCrc>", entry.frameTag)
    }

    @Test
    fun `all frame tags match CrcCalculator output`() {
        repository.initialize(isMyAir5 = true)
        val expectedTags =
            listOf(
                "getSystemData",
                "getClock",
                "getZoneData?zone=1",
                "getZoneData?zone=2",
                "getZoneData?zone=3",
                "getZoneData?zone=4",
                "getZoneData?zone=5",
                "getZoneData?zone=6",
                "getZoneData?zone=7",
                "getZoneData?zone=8",
                "getZoneData?zone=9",
                "getZoneData?zone=10",
            )
        for (tag in expectedTags) {
            val expectedCrc = CrcCalculator.computeHex(tag)
            val expectedFrameTag = "<U>$tag</U=$expectedCrc>"
            val entry = repository.currentPoll()
            assertEquals(expectedFrameTag, entry?.frameTag)
            repository.advanceToNext()
        }
    }

    @Test
    fun `initialize with non-MyAir5 adds schedule items`() {
        repository.initialize(isMyAir5 = false)
        // 12 base + 6 schedule = 18
        assertEquals(18, repository.queueSize())
        val entry = repository.currentPoll()
        assertNotNull(entry)
        assertEquals("getSystemData", entry?.tag)
    }

    @Test
    fun `advanceToNext on empty queue returns null`() {
        assertNull(repository.advanceToNext())
    }

    @Test
    fun `queueSize reflects number of entries`() {
        repository.initialize(isMyAir5 = true)
        assertEquals(12, repository.queueSize())
        repository.initialize(isMyAir5 = false)
        assertEquals(18, repository.queueSize())
    }
}
