package com.air.advantage.aaservice.data.mailbox

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Wire `type` string constants for mailbox WebSocket frames. */
object MailboxMessageType {
    const val MAILBOX_SNAPSHOT = "mailbox_snapshot"
    const val MAILBOX_EVENT = "mailbox_event"
    const val ACK = "ack"
    const val ERROR = "error"
    const val MAILBOX_UPDATE = "mailbox_update"
    const val COMMAND = "command"
    const val WRITE_CAN = "write_can"
    const val DIRECT = "direct"
    const val RAW_CAN = "raw_can"
    const val DIRECT_REPLY = "direct_reply"
}

/** Known `action` values for outbound `command` frames. */
object MailboxCommandAction {
    const val RESYNC_MAILBOX = "resync_mailbox"
}

/** Ack frame `status` values. */
enum class MailboxAckStatus {
    SUCCESS,
    ERROR,
    ;

    companion object {
        fun fromWire(value: String?): MailboxAckStatus? = when (value) {
            "success" -> SUCCESS
            "error" -> ERROR
            else -> null
        }
    }

    fun toWire(): String = when (this) {
        SUCCESS -> "success"
        ERROR -> "error"
    }
}

/**
 * Server → client mailbox frames.
 *
 * Payload / register fields stay loosely typed ([JSONObject]) so A4 can own
 * schema details. Unknown `type` values become [Unknown] for log-and-ignore.
 */
sealed class MailboxInbound {
    abstract val type: String
    abstract val raw: JSONObject

    /** Full bank on connect / after resync. Register fields remain on [raw]. */
    data class Snapshot(
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.MAILBOX_SNAPSHOT
        val unitId: String? get() = raw.optStringOrNull("unit_id")
    }

    data class Event(
        val register: String?,
        val payload: JSONObject?,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.MAILBOX_EVENT
    }

    /** Raw steady-state `getCAN` frame (`getCAN 1 <records…>`) — USB rawCan parity. */
    data class RawCan(
        val payload: String,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.RAW_CAN
    }

    /** Reply to a one-shot [`MailboxOutbound.Direct`] request (CB XML/raw payload). */
    data class DirectReply(
        val payload: String,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.DIRECT_REPLY
    }

    data class Ack(
        val msgId: String?,
        val status: MailboxAckStatus?,
        val reason: String?,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.ACK
    }

    /** Optional recoverable protocol/client error — does not fail the socket. */
    data class Error(
        val message: String?,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.ERROR
    }

    data class Unknown(
        override val type: String,
        override val raw: JSONObject,
    ) : MailboxInbound()

    companion object {
        /**
         * Parse a WebSocket text frame into a typed inbound message.
         * @throws JSONException if [text] is not a JSON object
         */
        fun parse(text: String): MailboxInbound = parse(JSONObject(text))

        fun parse(json: JSONObject): MailboxInbound {
            val type = json.optString("type", "")
            return when (type) {
                MailboxMessageType.MAILBOX_SNAPSHOT -> Snapshot(raw = json)
                MailboxMessageType.MAILBOX_EVENT -> Event(
                    register = json.optStringOrNull("register"),
                    payload = json.optJSONObject("payload"),
                    raw = json,
                )
                MailboxMessageType.RAW_CAN -> RawCan(
                    payload = json.optString("payload", ""),
                    raw = json,
                )
                MailboxMessageType.DIRECT_REPLY -> DirectReply(
                    payload = json.optString("payload", ""),
                    raw = json,
                )
                MailboxMessageType.ACK -> Ack(
                    msgId = json.optStringOrNull("msg_id"),
                    status = MailboxAckStatus.fromWire(json.optStringOrNull("status")),
                    reason = json.optStringOrNull("reason"),
                    raw = json,
                )
                MailboxMessageType.ERROR -> Error(
                    message = json.optStringOrNull("message")
                        ?: json.optStringOrNull("reason"),
                    raw = json,
                )
                else -> Unknown(type = type, raw = json)
            }
        }
    }
}

/**
 * Client → server mailbox frames.
 */
sealed class MailboxOutbound {
    abstract val type: String
    abstract val msgId: String

    fun toJsonString(): String = toJson().toString()

    abstract fun toJson(): JSONObject

    data class Update(
        override val msgId: String,
        val register: String,
        val payload: JSONObject,
    ) : MailboxOutbound() {
        override val type: String get() = MailboxMessageType.MAILBOX_UPDATE

        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            put("msg_id", msgId)
            put("register", register)
            put("payload", payload)
        }
    }

    data class Resync(
        override val msgId: String,
    ) : MailboxOutbound() {
        override val type: String get() = MailboxMessageType.COMMAND
        val action: String get() = MailboxCommandAction.RESYNC_MAILBOX

        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            put("msg_id", msgId)
            put("action", action)
        }
    }

    /** Raw CAN2 token write (25-char hex records) — CAN_TO_CB / BROADCAST_CAN_TO_CB parity. */
    data class WriteCan(
        override val msgId: String,
        val tokens: List<String>,
    ) : MailboxOutbound() {
        override val type: String get() = MailboxMessageType.WRITE_CAN

        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            put("msg_id", msgId)
            put("tokens", JSONArray(tokens))
        }
    }

    /** One-shot raw request (poll tag / `setAllZoneSensorData?`); reply is a DirectReply. */
    data class Direct(
        override val msgId: String,
        val payload: String,
    ) : MailboxOutbound() {
        override val type: String get() = MailboxMessageType.DIRECT

        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            put("msg_id", msgId)
            put("payload", payload)
        }
    }

    companion object {
        fun update(msgId: String, register: String, payload: JSONObject): Update =
            Update(msgId = msgId, register = register, payload = payload)

        fun resyncMailbox(msgId: String): Resync = Resync(msgId = msgId)

        fun writeCan(msgId: String, tokens: List<String>): WriteCan =
            WriteCan(msgId = msgId, tokens = tokens)

        fun direct(msgId: String, payload: String): Direct =
            Direct(msgId = msgId, payload = payload)
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return value.ifEmpty { null }
}
