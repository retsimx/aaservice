package com.air.advantage.aaservice.domain.state

import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.data.protocol.FrameParser
import com.air.advantage.aaservice.domain.transform.GetSystemDataTransformer
import java.nio.charset.StandardCharsets

/**
 * Outbound sink for decoded data produced by [UartDispatchEngine].
 */
interface UartEventSink {

    /**
     * Called with a decoded poll response whose payload changed since the last delivery
     * for the same [tag]. For `getSystemData` the payload has already been transformed.
     */
    fun onPollData(tag: String, payload: ByteArray)

    /**
     * Called with a raw `getCAN ` payload that did not require a retry.
     */
    fun onRawCan(payload: ByteArray)
}

/**
 * Pure-Kotlin implementation of the reference UART dispatch engine — the priority sender
 * (`ServiceUart$k.d()`) and the frame processor (`k.java RunnableParseMessage.run()`).
 *
 * The engine owns the poll list/index, the CAN id queue, the direct-message queue and all
 * retry/ack state. Outbound frames use the wire format `<U>{content}</U={crc}>` where the
 * CRC is computed by [CrcCalculator.computeHex].
 *
 * Priority on every ping: ackCAN reply, then CAN (`setCAN`/retry), then the direct-message
 * queue, then the poll entry at `currentPollIndex()`. The poll index advances only inside
 * [onFrame] when an inbound payload's `<request>` matches the current poll tag.
 */
class UartDispatchEngine(
    pollTags: List<String>,
    private val typeBytes: ByteArray,
    private val appStoreBytes: ByteArray?,
    private val sink: UartEventSink,
    private val logger: (String) -> Unit = {}
) {

    private val lock = Any()

    private val parser = FrameParser()

    private val pollList: List<String> = pollTags.map { framed(it) }

    private val canQueue = ArrayDeque<String>()
    private val directQueue = ArrayDeque<String>()

    private var pollIndex = 0
    private var ackCanPending = false
    private var lastCrcOk = true
    private var canWanted = false
    private var canInUse = false
    private var canRetry = false
    private var canRetryCount = 0
    private var canMessageArmed = false
    private var lastCanFrame: ByteArray? = null
    private var lastSentDirect = ""
    private var directRetryCount = 0
    private var directResendCount = 0
    private var canUnsupported = false
    private var expectingAck = false

    private val responseCache = mutableMapOf<String, ByteArray>()

    /**
     * Produces exactly one outbound frame for the next ping, or `null` when nothing should
     * be written. Priority: ackCAN → CAN (retry / `setCAN`) → direct queue → poll.
     */
    fun onPing(): ByteArray? {
        synchronized(lock) {
            if (ackCanPending) {
                val content = "ackCAN " + if (lastCrcOk) "1" else "0"
                val frame = framed(content).toByteArray(StandardCharsets.UTF_8)
                ackCanPending = false
                expectingAck = true
                return frame
            }

            if (canWanted || canInUse) {
                val frame = if (canRetry || canMessageArmed) {
                    lastCanFrame ?: buildSetCanFrame()
                } else {
                    buildSetCanFrame()
                }
                canWanted = false
                expectingAck = true
                return frame
            }

            expectingAck = true
            if (!canUnsupported) {
                canWanted = true
            }

            if (directQueue.isNotEmpty()) {
                val head = directQueue.first()
                if (head == lastSentDirect) {
                    directResendCount++
                } else {
                    directResendCount = 0
                }
                if (directResendCount > 2) {
                    directQueue.removeFirst()
                    return null
                }
                if (directRetryCount < 15) {
                    lastSentDirect = head
                    directRetryCount++
                    return head.toByteArray(StandardCharsets.UTF_8)
                }
                pollIndex = 0
                canUnsupported = false
            }

            directRetryCount = 0
            lastSentDirect = ""
            return pollList.getOrNull(pollIndex)?.toByteArray(StandardCharsets.UTF_8)
        }
    }

    /**
     * Processes one CRC-validated inbound frame payload. `getCAN`/`rawCan` payloads never
     * advance the poll index; only a matching poll `<request>` does.
     */
    fun onFrame(payload: ByteArray) {
        var rawCan: ByteArray? = null
        var pollDelivery: Pair<String, ByteArray>? = null
        synchronized(lock) {
            val text = String(payload, StandardCharsets.UTF_8)

            if (parser.isNack(payload) >= 0) {
                if (expectingAck) {
                    logger("Warning got a failed ack back")
                }
                if (canRetryCount < 3) {
                    canRetryCount++
                    canRetry = true
                } else {
                    canRetryCount = 0
                    canRetry = false
                }
                canMessageArmed = false
                if (parser.isUnknown(payload) >= 0) {
                    logger("CB doesn't support can messages")
                    canUnsupported = true
                    return
                }
            } else if (parser.isAck(payload) >= 0) {
                logger("Got a successful ack back")
            }

            if (parser.isGetCan(payload) >= 0) {
                ackCanPending = true
                canMessageArmed = false
                if (payload.size <= 9) {
                    return
                }
                val retryNeeded = payload[7] == ZERO_BYTE
                if (retryNeeded && canRetryCount < 3) {
                    canRetryCount++
                    canRetry = true
                    return
                }
                canRetryCount = 0
                canRetry = false
                rawCan = payload
            } else if (parser.isUnknown(payload) >= 0) {
                logger("Just got reply unknown")
            } else if (lastSentDirect.isNotEmpty()) {
                val expectedTag = matchTag(lastSentDirect)
                val requestContent = extractRequest(payload)
                if (expectedTag == requestContent) {
                    if (directQueue.isEmpty()) {
                        logger("Trying to remove a message from the queue that doesn't exist. $expectedTag $text")
                    } else {
                        directQueue.removeFirst()
                    }
                } else if (text == CAN2_IN_USE) {
                    canInUse = true
                } else {
                    logger("request and returned value don't match - $expectedTag $text")
                }
            } else if (pollList.isEmpty() || pollIndex >= pollList.size) {
                logger("poll number issue")
            } else {
                val pollFrame = pollList[pollIndex]
                val expectedTag = matchTag(pollFrame)
                val requestContent = extractRequest(payload)
                if (expectedTag != requestContent) {
                    if (text == CAN2_IN_USE) {
                        canInUse = true
                    } else {
                        logger("poll request and returned value don't match - $expectedTag $text")
                    }
                } else {
                    val tag = pollFrame.substring(3, pollFrame.length - 7)
                    pollIndex++
                    if (pollIndex >= pollList.size) {
                        pollIndex = 0
                        canUnsupported = false
                    }

                    var broadcastPayload = payload
                    if (tag == "getSystemData") {
                        val transformed = GetSystemDataTransformer.transform(payload, typeBytes, appStoreBytes)
                            ?: return
                        broadcastPayload = transformed
                    }

                    val cached = responseCache[tag]
                    if (cached != null && parser.isEqual(cached, broadcastPayload)) {
                        return
                    }
                    responseCache[tag] = broadcastPayload
                    pollDelivery = tag to broadcastPayload
                }
            }
        }

        rawCan?.let { sink.onRawCan(it) }
        pollDelivery?.let { sink.onPollData(it.first, it.second) }
    }

    /**
     * Frames [content] and adds it to the direct-message queue (deduplicated against the
     * existing queue, mirroring the reference `a(String)` behavior).
     */
    fun enqueueDirectMessage(content: String) {
        val framedMessage = framed(content)
        synchronized(lock) {
            directQueue.removeAll { it == framedMessage }
            directQueue.addLast(framedMessage)
        }
    }

    /**
     * Adds CAN ids to the CAN queue, skipping ids already queued.
     */
    fun enqueueCanIds(ids: List<Int>) {
        synchronized(lock) {
            for (id in ids) {
                val idStr = id.toString()
                if (!canQueue.contains(idStr)) {
                    canQueue.addLast(idStr)
                }
            }
        }
    }

    /**
     * Resets all engine state and clears the response cache.
     */
    fun reset() {
        synchronized(lock) {
            pollIndex = 0
            canQueue.clear()
            directQueue.clear()
            ackCanPending = false
            lastCrcOk = true
            canWanted = false
            canInUse = false
            canRetry = false
            canRetryCount = 0
            canMessageArmed = false
            lastCanFrame = null
            lastSentDirect = ""
            directRetryCount = 0
            directResendCount = 0
            canUnsupported = false
            expectingAck = false
            responseCache.clear()
        }
    }

    /**
     * The poll list index that [onPing] currently sends; advanced only by [onFrame].
     */
    fun currentPollIndex(): Int = synchronized(lock) { pollIndex }

    /**
     * Records whether the most recent inbound frame passed CRC validation. Feeds the
     * [lastCrcOk] bit used by the outbound `ackCAN 0|1` reply.
     */
    fun setCrcOk(ok: Boolean) {
        synchronized(lock) {
            lastCrcOk = ok
        }
    }

    private fun buildSetCanFrame(): ByteArray {
        val sb = StringBuilder("setCAN ")
        var popped = 0
        while (popped < 25 && canQueue.isNotEmpty()) {
            if (popped != 0) {
                sb.append(" ")
            }
            sb.append(canQueue.removeFirst())
            popped++
        }
        if (popped == 0 && directQueue.isNotEmpty()) {
            sb.append(directQueue.removeFirst())
        }
        val bytes = framed(sb.toString()).toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > 17) {
            lastCanFrame = bytes
            canMessageArmed = true
        }
        return bytes
    }

    private fun framed(content: String): String =
        "<U>$content</U=${CrcCalculator.computeHex(content)}>"

    private fun matchTag(framedMessage: String): String {
        val stripped = framedMessage.substring(3, framedMessage.length - 7)
        val query = stripped.indexOf('?')
        return if (query >= 0) stripped.substring(0, query) else stripped
    }

    private fun extractRequest(payload: ByteArray): String = try {
        parser.extractTag(payload, REQUEST_BYTES)
    } catch (e: IllegalArgumentException) {
        ""
    }

    private companion object {
        val REQUEST_BYTES: ByteArray = "request".toByteArray(StandardCharsets.UTF_8)
        val ZERO_BYTE: Byte = '0'.code.toByte()
        const val CAN2_IN_USE: String = "CAN2 in use"
    }
}
