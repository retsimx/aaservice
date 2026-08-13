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
    fun onPollData(
        tag: String,
        payload: ByteArray,
    )

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
    private val logger: (String) -> Unit = {},
) {
    private val lock = Any()

    private val parser = FrameParser()

    private val pollList: List<String> = pollTags.map { framed(it) }

    private val canQueue = ArrayDeque<String>()
    private val broadcastCanQueue = ArrayDeque<String>()
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
                val retryOrArmed = canRetry || canMessageArmed
                val hasQueued = canQueue.isNotEmpty() || broadcastCanQueue.isNotEmpty()
                if (retryOrArmed || hasQueued) {
                    val frame =
                        if (retryOrArmed) {
                            lastCanFrame ?: buildSetCanFrame()
                        } else {
                            buildSetCanFrame()
                        }
                    canWanted = false
                    expectingAck = true
                    return frame
                }
                // Stock still emits empty `setCAN `, which on live hardware leaves the CB in
                // "CAN2 in use" and permanently starves getSystemData. Skip the empty frame.
                canWanted = false
                if (canInUse) {
                    logger("CAN2 in use but CAN queues empty — skipping empty setCAN")
                }
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
                    logger("direct message resend limit exceeded, dropping: $head")
                    directQueue.removeFirst()
                    return null
                }
                if (directRetryCount < 15) {
                    lastSentDirect = head
                    directRetryCount++
                    return head.toByteArray(StandardCharsets.UTF_8)
                }
                logger("direct message retry limit reached, falling back to poll: $head")
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
                // CAN transaction finished (or empty getCAN) — bus is free for poll again.
                // Stock never clears f4154g; without this, a single "CAN2 in use" poll reply
                // permanently starves getSystemData / zone polls.
                canInUse = false
                if (payload.size <= 9) {
                    return
                }
                val retryNeeded = payload[7] == ZERO_BYTE
                if (retryNeeded && canRetryCount < 3) {
                    canRetryCount++
                    canRetry = true
                    logger("CAN retry needed (count=$canRetryCount)")
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
                    logger("CAN2 in use (direct path)")
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
                        logger("CAN2 in use (poll path, expected=$expectedTag)")
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
                        val transformed =
                            GetSystemDataTransformer.transform(payload, typeBytes, appStoreBytes)
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
     * Adds CAN tokens to the CAN queue, skipping tokens already queued.
     * Tokens are opaque wire strings (decimal ids or hex blobs like
     * `0701000000600000000000000`), matching stock `C.a` / `ServiceUart.i()`.
     */
    fun enqueueCanIds(ids: List<String>) {
        synchronized(lock) {
            for (id in ids) {
                if (!canQueue.contains(id)) {
                    canQueue.addLast(id)
                }
            }
        }
    }

    /**
     * Adds broadcast CAN tokens to the dedicated broadcast queue, skipping tokens already
     * queued. Mirrors the reference `ServiceUart.f4131f` list, which is only consulted as a
     * fallback when the CAN queue is empty.
     */
    fun enqueueBroadcastCanIds(ids: List<String>) {
        synchronized(lock) {
            for (id in ids) {
                if (!broadcastCanQueue.contains(id)) {
                    broadcastCanQueue.addLast(id)
                }
            }
        }
    }

    /**
     * Arms the outbound `ackCAN` reply without an inbound frame having passed CRC validation.
     * Mirrors the reference read-loop arming (`ServiceUart$k.e()`), which sets `f4169v = true`
     * whenever `getCAN ` appears in the buffer regardless of the CRC outcome.
     */
    fun armAckCan() {
        synchronized(lock) {
            ackCanPending = true
            canMessageArmed = false
        }
    }

    /**
     * Resets all engine state and clears the response cache.
     */
    fun reset() {
        synchronized(lock) {
            pollIndex = 0
            canQueue.clear()
            broadcastCanQueue.clear()
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
        if (popped == 0 && broadcastCanQueue.isNotEmpty()) {
            sb.append(broadcastCanQueue.removeFirst())
        }
        val bytes = framed(sb.toString()).toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > 17) {
            lastCanFrame = bytes
            canMessageArmed = true
        }
        return bytes
    }

    private fun framed(content: String): String = "<U>$content</U=${CrcCalculator.computeHex(content)}>"

    private fun matchTag(framedMessage: String): String {
        val stripped = framedMessage.substring(3, framedMessage.length - 7)
        val query = stripped.indexOf('?')
        return if (query >= 0) stripped.substring(0, query) else stripped
    }

    private fun extractRequest(payload: ByteArray): String =
        try {
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
