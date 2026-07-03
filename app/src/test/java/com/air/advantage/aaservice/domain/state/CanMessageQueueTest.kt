package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.domain.model.CanMessage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CanMessageQueueTest {
    
    private lateinit var queue: CanMessageQueue
    
    @Before
    fun setUp() {
        queue = CanMessageQueue()
    }
    
    @Test
    fun `empty queue returns isEmpty true`() {
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }
    
    @Test
    fun `enqueue increases size`() {
        queue.enqueue(CanMessage(1, "test"))
        assertFalse(queue.isEmpty())
        assertEquals(1, queue.size())
    }
    
    @Test
    fun `dequeue returns oldest message`() {
        queue.enqueue(CanMessage(1, "first"))
        queue.enqueue(CanMessage(2, "second"))
        val dequeued = queue.dequeue()
        assertEquals(1, dequeued?.id)
        assertEquals("first", dequeued?.data)
    }
    
    @Test
    fun `peek returns oldest without removing`() {
        queue.enqueue(CanMessage(1, "test"))
        val peeked = queue.peek()
        assertEquals(1, peeked?.id)
        assertEquals(1, queue.size()) // still in queue
    }
    
    @Test
    fun `buildCanFrame formats correctly`() {
        queue.enqueue(CanMessage(1, "test1"))
        queue.enqueue(CanMessage(2, "test2"))
        queue.enqueue(CanMessage(3, "test3"))
        val frame = queue.buildCanFrame()
        assertEquals("setCAN 1 2 3", frame)
    }
    
    @Test
    fun `buildCanFrame returns empty for empty queue`() {
        assertEquals("", queue.buildCanFrame())
    }
    
    @Test
    fun `max 25 CAN IDs enforced`() {
        for (i in 1..25) {
            queue.enqueue(CanMessage(i, "test"))
        }
        val frame = queue.buildCanFrame()
        assertTrue(frame.startsWith("setCAN"))
        val ids = frame.removePrefix("setCAN ").split(" ")
        assertEquals(25, ids.size)
    }
    
    @Test
    fun `clear empties queue`() {
        queue.enqueue(CanMessage(1, "test"))
        queue.clear()
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }
    
    @Test
    fun `dequeue on empty queue returns null`() {
        assertNull(queue.dequeue())
    }
    
    @Test
    fun `peek on empty queue returns null`() {
        assertNull(queue.peek())
    }
}
