package com.air.advantage.aaservice.data.protocol

import java.util.Arrays

class FrameParser {

    private val ack0: ByteArray = "<ack>0</ack>".toByteArray(Charsets.UTF_8)
    private val ack1: ByteArray = "<ack>1</ack>".toByteArray(Charsets.UTF_8)
    private val endOne: ByteArray = "</U=".toByteArray(Charsets.UTF_8)
    private val gt: ByteArray = ">".toByteArray(Charsets.UTF_8)
    private val getCan: ByteArray = "getCAN ".toByteArray(Charsets.UTF_8)
    private val ping: ByteArray = "<U>Ping</U=db>".toByteArray(Charsets.UTF_8)
    private val startU: ByteArray = "<U>".toByteArray(Charsets.UTF_8)
    private val unknown: ByteArray = "<request>Unknown</request>".toByteArray(Charsets.UTF_8)

    fun findStartMarker(data: ByteArray): Int {
        return indexOf(0, data, startU)
    }

    fun findEndMarker(offset: Int, data: ByteArray): Int {
        val pos = indexOf(offset, data, ping)
        return if (pos >= 0) pos + ping.size else -1
    }

    fun findFrameEnd(offset: Int, data: ByteArray): Int {
        if (data.size <= offset + 7) return -1
        val endPos = indexOf(offset, data, endOne)
        if (endPos <= 0) return -1
        val gtPos = indexOf(endPos, data, gt)
        if (gtPos <= 0) return -1
        return gtPos + 1
    }

    fun isNack(data: ByteArray): Int {
        return indexOf(0, data, ack0)
    }

    fun isAck(data: ByteArray): Int {
        return indexOf(0, data, ack1)
    }

    fun isGetCan(data: ByteArray): Int {
        return indexOf(0, data, getCan)
    }

    fun isUnknown(data: ByteArray): Int {
        return indexOf(0, data, unknown)
    }

    fun extractTag(data: ByteArray, tag: ByteArray): String {
        val content = extractTagContent(data, tag)
        return String(content)
    }

    fun replaceTagContent(data: ByteArray, tag: ByteArray, content: ByteArray): ByteArray {
        val openTag = openTag(tag)
        val closeTag = closeTag(tag)
        val openPos = indexOf(0, data, openTag)
        if (openPos < 0) throw IllegalArgumentException("XML tag not found")
        val closePos = indexOf(openPos + openTag.size, data, closeTag)
        if (closePos < 0) throw IllegalArgumentException("XML tag not found")
        val startContent = openPos + openTag.size
        val result = ByteArray(startContent + content.size + (data.size - closePos))
        System.arraycopy(data, 0, result, 0, startContent)
        System.arraycopy(content, 0, result, startContent, content.size)
        System.arraycopy(data, closePos, result, startContent + content.size, data.size - closePos)
        return result
    }

    fun removeTag(data: ByteArray, tag: ByteArray, replacement: ByteArray): ByteArray {
        val openTag = openTag(tag)
        val openPos = indexOf(0, data, openTag)
        if (openPos <= 0) return data
        val closeTag = closeTag(replacement)
        val closePos = indexOf(openPos, data, closeTag)
        if (closePos < 0) return data
        val closeEnd = closePos + closeTag.size
        if (openTag.size + openPos < closeEnd) {
            val result = ByteArray((data.size - closeEnd) + openPos)
            System.arraycopy(data, 0, result, 0, openPos)
            System.arraycopy(data, closeEnd, result, openPos, data.size - closeEnd)
            return result
        }
        return data
    }

    fun parseHexByte(position: Int, data: ByteArray): Int {
        val high = hexDigit(data[position - 3])
        val low = hexDigit(data[position - 2])
        if (high < 0 || low < 0) return -1
        return (high * 16) + low
    }

    fun extractPayload(data: ByteArray, start: Int, end: Int): ByteArray? {
        val length = end - start
        if (data.size - start < length) return null
        val result = ByteArray(length)
        System.arraycopy(data, start, result, 0, length)
        return result
    }

    fun isEqual(a: ByteArray?, b: ByteArray?): Boolean {
        return if (a == null || b == null) false else Arrays.equals(a, b)
    }

    fun shiftBuffer(position: Int, data: ByteArray) {
        var i = 0
        var pos = position
        while (pos < data.size && data[pos] != 0.toByte()) {
            data[i] = data[pos]
            i++
            pos++
        }
        while (i < data.size && data[i] != 0.toByte()) {
            data[i] = 0
            i++
        }
    }

    fun indexOf(start: Int, data: ByteArray?, pattern: ByteArray?): Int {
        if (data == null || pattern == null) return -1
        if (data.size < pattern.size) return -1
        if (start >= data.size) return -1
        if (data[start] == 0.toByte()) return -1

        var pos = start
        while (pos <= data.size - pattern.size) {
            var match = true
            for (i in pattern.indices) {
                if (data[pos + i] != pattern[i]) {
                    match = false
                    break
                }
            }
            if (match) return pos
            pos++
            if (pos < data.size && data[pos - 1] == 0.toByte()) return -1
        }
        return -1
    }

    fun openTag(tag: ByteArray): ByteArray {
        val result = ByteArray(tag.size + 2)
        result[0] = 60
        result[tag.size + 1] = 62
        System.arraycopy(tag, 0, result, 1, tag.size)
        return result
    }

    fun closeTag(tag: ByteArray): ByteArray {
        val result = ByteArray(tag.size + 3)
        result[0] = 60
        result[1] = 47
        result[tag.size + 2] = 62
        System.arraycopy(tag, 0, result, 2, tag.size)
        return result
    }

    fun extractTagContent(data: ByteArray, tag: ByteArray): ByteArray {
        val openTag = openTag(tag)
        val openPos = indexOf(0, data, openTag)
        if (openPos < 0) throw IllegalArgumentException("XML tag not found")
        val closeTag = closeTag(tag)
        val closePos = indexOf(openPos, data, closeTag)
        if (openTag.size + openPos > closePos) throw IllegalArgumentException("XML tag not found")
        if (closePos > openTag.size + openPos) {
            return Arrays.copyOfRange(data, openPos + openTag.size, closePos)
        }
        return ByteArray(0)
    }

    private fun hexDigit(b: Byte): Int {
        val v = b.toInt()
        if (v < 48) return -1
        if (v < 58) return v - 48
        if (v in 97..102) return v - 87
        return -1
    }
}
