package com.air.advantage.aaservice.data.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameParserTest {

    private val parser = FrameParser()

    // --- isAck / isNack ---

    @Test
    fun `isAck returns 0 when ack1 found at start`() {
        assertEquals(0, parser.isAck("<ack>1</ack>".toByteArray()))
    }

    @Test
    fun `isAck returns -1 when ack1 not found`() {
        assertEquals(-1, parser.isAck("<ack>0</ack>".toByteArray()))
    }

    @Test
    fun `isNack returns 0 when ack0 found at start`() {
        assertEquals(0, parser.isNack("<ack>0</ack>".toByteArray()))
    }

    @Test
    fun `isNack returns -1 when ack0 not found`() {
        assertEquals(-1, parser.isNack("<ack>1</ack>".toByteArray()))
    }

    // --- isGetCan ---

    @Test
    fun `isGetCan returns 0 when getCAN found at start`() {
        assertEquals(0, parser.isGetCan("getCAN hello".toByteArray()))
    }

    @Test
    fun `isGetCan returns -1 when getCAN not found`() {
        assertEquals(-1, parser.isGetCan("something else".toByteArray()))
    }

    // --- isUnknown ---

    @Test
    fun `isUnknown returns 0 when unknown tag found`() {
        assertEquals(0, parser.isUnknown("<request>Unknown</request>".toByteArray()))
    }

    @Test
    fun `isUnknown returns -1 when unknown tag not found`() {
        assertEquals(-1, parser.isUnknown("<request>Known</request>".toByteArray()))
    }

    // --- findStartMarker ---

    @Test
    fun `findStartMarker returns 0 when U tag at start`() {
        assertEquals(0, parser.findStartMarker("<U>test</U=ab>".toByteArray()))
    }

    @Test
    fun `findStartMarker returns -1 when U tag not found`() {
        assertEquals(-1, parser.findStartMarker("no tag here".toByteArray()))
    }

    @Test
    fun `findStartMarker returns correct offset when U tag not at start`() {
        assertEquals(5, parser.findStartMarker("hello<U>test</U=db>".toByteArray()))
    }

    // --- extractTag ---

    @Test
    fun `extractTag returns content between tags`() {
        assertEquals("hello", parser.extractTag("<request>hello</request>".toByteArray(), "request".toByteArray()))
    }

    @Test
    fun `extractTag returns empty string for empty tag content`() {
        assertEquals("", parser.extractTag("<request></request>".toByteArray(), "request".toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `extractTag throws when tag not found`() {
        parser.extractTag("<other>hello</other>".toByteArray(), "request".toByteArray())
    }

    // --- indexOf null terminator behavior ---

    @Test
    fun `indexOf returns -1 when null byte encountered before pattern`() {
        val data = byteArrayOf(0x41, 0x00, 0x42, 0x43)
        assertEquals(-1, parser.indexOf(0, data, byteArrayOf(0x42, 0x43)))
    }

    @Test
    fun `indexOf returns correct position when pattern found`() {
        val data = "hello world".toByteArray()
        assertEquals(6, parser.indexOf(0, data, "world".toByteArray()))
    }

    @Test
    fun `indexOf returns -1 for null data`() {
        assertEquals(-1, parser.indexOf(0, null, "test".toByteArray()))
    }

    @Test
    fun `indexOf returns -1 for null pattern`() {
        assertEquals(-1, parser.indexOf(0, "test".toByteArray(), null))
    }

    @Test
    fun `indexOf returns -1 when data smaller than pattern`() {
        assertEquals(-1, parser.indexOf(0, "ab".toByteArray(), "abcdef".toByteArray()))
    }

    @Test
    fun `indexOf returns -1 when start is beyond data size`() {
        assertEquals(-1, parser.indexOf(10, "test".toByteArray(), "t".toByteArray()))
    }

    // --- shiftBuffer ---

    @Test
    fun `shiftBuffer copies bytes from position until null then zeros remainder`() {
        // shiftBuffer copies data[pos..] to start until null, then zeros from i until null
        val data = byteArrayOf(0x41, 0x42, 0x43, 0x00, 0x44, 0x45, 0x00, 0x00)
        parser.shiftBuffer(0, data)
        // First loop copies 0x41,0x42,0x43 to [0..2], stops at null at pos 3
        // Second loop: i=3, data[3]=0 so exits immediately
        assertEquals(0x41.toByte(), data[0])
        assertEquals(0x42.toByte(), data[1])
        assertEquals(0x43.toByte(), data[2])
    }

    @Test
    fun `shiftBuffer zeros processed bytes when position past initial data`() {
        val data = byteArrayOf(0x41, 0x42, 0x43, 0x00, 0x44, 0x45, 0x00, 0x00)
        parser.shiftBuffer(3, data)
        // First loop: pos=3, data[3]=0 → exits (i=0)
        // Second loop: zeros data[0..2] since they are non-null
        assertEquals(0x00.toByte(), data[0])
        assertEquals(0x00.toByte(), data[1])
        assertEquals(0x00.toByte(), data[2])
    }

    @Test
    fun `shiftBuffer with no null bytes shifts nothing`() {
        val data = byteArrayOf(0x41, 0x42, 0x43, 0x00, 0x00, 0x00, 0x00, 0x00)
        parser.shiftBuffer(0, data)
        assertEquals(0x41.toByte(), data[0])
        assertEquals(0x42.toByte(), data[1])
        assertEquals(0x43.toByte(), data[2])
    }

    // --- isEqual ---

    @Test
    fun `isEqual returns true for identical arrays`() {
        assertTrue(parser.isEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `isEqual returns false for different arrays`() {
        assertFalse(parser.isEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
    }

    @Test
    fun `isEqual returns false when either array is null`() {
        assertFalse(parser.isEqual(null, byteArrayOf(1, 2, 3)))
        assertFalse(parser.isEqual(byteArrayOf(1, 2, 3), null))
        assertFalse(parser.isEqual(null, null))
    }

    // --- extractPayload ---

    @Test
    fun `extractPayload returns correct bytes for valid range`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val result = parser.extractPayload(data, 1, 3)
        assertNotNull(result)
        assertArrayEquals(byteArrayOf(0x02, 0x03), result)
    }

    @Test
    fun `extractPayload returns null when range exceeds data size`() {
        val data = byteArrayOf(0x01, 0x02)
        assertNull(parser.extractPayload(data, 0, 5))
    }

    @Test
    fun `extractPayload returns empty array for zero-length range`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val result = parser.extractPayload(data, 1, 1)
        assertNotNull(result)
        assertEquals(0, result!!.size)
    }

    // --- openTag / closeTag ---

    @Test
    fun `openTag wraps bytes with angle brackets`() {
        val result = parser.openTag("test".toByteArray())
        assertArrayEquals("<test>".toByteArray(), result)
    }

    @Test
    fun `closeTag wraps bytes with closing angle brackets`() {
        val result = parser.closeTag("test".toByteArray())
        assertArrayEquals("</test>".toByteArray(), result)
    }

    // --- parseHexByte ---

    @Test
    fun `parseHexByte returns correct value for valid hex`() {
        // parseHexByte(position) reads data[position-3] (high) and data[position-2] (low)
        // Only lowercase a-f and digits 0-9 are valid
        val data = byteArrayOf(0x00, 0x00, 'a'.code.toByte(), 'f'.code.toByte(), 0x00)
        // position=5: high=data[2]='a'(10), low=data[3]='f'(15) → 10*16+15=175
        assertEquals(175, parser.parseHexByte(5, data))
    }

    @Test
    fun `parseHexByte returns -1 for invalid hex`() {
        val data = byteArrayOf(0x00, 0x00, 'G'.code.toByte(), '0'.code.toByte(), 0x00)
        assertEquals(-1, parser.parseHexByte(4, data))
    }

    // --- replaceTagContent ---

    @Test
    fun `replaceTagContent replaces tag content at start`() {
        val xml = "<tag>old</tag><other>val</other>".toByteArray()
        val result = parser.replaceTagContent(xml, "tag".toByteArray(), "new".toByteArray())
        assertEquals("<tag>new</tag><other>val</other>", String(result))
    }

    @Test
    fun `replaceTagContent replaces tag content in middle`() {
        val xml = "<first>1</first><tag>old</tag><last>2</last>".toByteArray()
        val result = parser.replaceTagContent(xml, "tag".toByteArray(), "new".toByteArray())
        assertEquals("<first>1</first><tag>new</tag><last>2</last>", String(result))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `replaceTagContent throws when tag not found`() {
        val xml = "<other>val</other>".toByteArray()
        parser.replaceTagContent(xml, "tag".toByteArray(), "new".toByteArray())
    }

    // --- removeRange ---

    @Test
    fun `removeRange removes from startTag through endTag inclusive spanning intermediate tags`() {
        val xml = "<first>1</first><start>val</start><mid>2</mid><end>3</end><last>4</last>".toByteArray()
        val result = parser.removeRange(xml, "start", "end")
        assertEquals("<first>1</first><last>4</last>", String(result))
    }

    @Test
    fun `removeRange returns same data when startTag absent`() {
        val xml = "<first>1</first>".toByteArray()
        val result = parser.removeRange(xml, "start", "start")
        assertEquals("<first>1</first>", String(result))
    }

    @Test
    fun `removeRange returns same data when endTag absent`() {
        val xml = "<first>1</first><start>val</start><last>4</last>".toByteArray()
        val result = parser.removeRange(xml, "start", "end")
        assertEquals("<first>1</first><start>val</start><last>4</last>", String(result))
    }

    // --- removeTag ---

    @Test
    fun `removeTag removes tag range`() {
        val xml = "<first>1</first><start>val</start><mid>2</mid><end>3</end><last>4</last>".toByteArray()
        // removeTag(data, tag, replacement): tag=start, replacement=end
        // should remove from <start> to </end> inclusive
        val result = parser.removeTag(xml, "start".toByteArray(), "end".toByteArray())
        assertEquals("<first>1</first><last>4</last>", String(result))
    }

    @Test
    fun `removeTag returns same data when tag not found`() {
        val xml = "<first>1</first>".toByteArray()
        val result = parser.removeTag(xml, "nonexistent".toByteArray(), "nonexistent".toByteArray())
        assertEquals("<first>1</first>", String(result))
    }

    @Test
    fun `removeTag returns same data when close tag absent`() {
        val xml = "<first>1</first><start>val</start><mid>2</mid><last>4</last>".toByteArray()
        val result = parser.removeTag(xml, "start".toByteArray(), "end".toByteArray())
        assertEquals("<first>1</first><start>val</start><mid>2</mid><last>4</last>", String(result))
    }

    @Test
    fun `removeTag returns same data when open tag absent`() {
        val xml = "<first>1</first>".toByteArray()
        val result = parser.removeTag(xml, "start".toByteArray(), "start".toByteArray())
        assertEquals("<first>1</first>", String(result))
    }
}
