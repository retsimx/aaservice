package com.air.advantage.aaservice.data.mailbox

import org.json.JSONException
import org.json.JSONObject

/** Wire `type` string constants for mailbox WebSocket frames. */
object MailboxMessageType {
    const val SNAPSHOT = "snapshot"
    const val EVENT = "event"
    const val READ_RESULT = "read_result"
    const val ACK = "ack"
    const val STATUS = "status"
    const val ERROR = "error"
    const val WRITE = "write"
    const val READ = "read"
    const val COMMAND = "command"
}

/** Known `action` values for outbound `command` frames. */
object MailboxCommandAction {
    const val RESYNC = "resync"
    const val FLUSH_UNIT = "flush_unit"
}

/** Ack frame `status` values. */
enum class MailboxAckStatus {
    SUCCESS,
    ERROR,
    ;

    companion object {
        fun fromWire(value: String?): MailboxAckStatus? =
            when (value) {
                "success" -> SUCCESS
                "error" -> ERROR
                else -> null
            }
    }

    fun toWire(): String =
        when (this) {
            SUCCESS -> "success"
            ERROR -> "error"
        }
}

/** Payload of an outbound [`MailboxOutbound.Write`] frame — typed object or raw hex string. */
sealed class MailboxPayload {
    data class Typed(val payload: JSONObject) : MailboxPayload()

    data class RawHex(val hex: String) : MailboxPayload()
}

/**
 * Server → client mailbox frames.
 *
 * Payload / register fields stay loosely typed ([JSONObject]) so the mapper
 * layer can own schema details. Unknown `type` values become [Unknown] for
 * log-and-ignore.
 */
sealed class MailboxInbound {
    abstract val type: String
    abstract val raw: JSONObject

    /**
     * Full register bank on connect / after resync, keyed
     * `"{unit_type}:{unit_id}"` → register → payload.
     *
     * [units] holds the typed object payloads (registered JSON objects);
     * [rawUnits] holds the raw-hex string payloads (14 lowercase hex chars,
     * keyed unit → register → hex) so passthrough registers survive parse
     * without reinterpretation — see the encoder's raw-hex merge.
     */
    data class Snapshot(
        val units: Map<String, Map<String, JSONObject>>,
        val rawUnits: Map<String, Map<String, String>>,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.SNAPSHOT
    }

    /** Register change pushed by the broker. Raw-hex payloads stay on [raw]. */
    data class Event(
        val unitType: String?,
        val unitId: String?,
        val register: String?,
        val zone: Int?,
        val payload: JSONObject?,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.EVENT
    }

    /** Reply to a one-shot [`MailboxOutbound.Read`] request. */
    data class ReadResult(
        val msgId: String?,
        val unitType: String?,
        val unitId: String?,
        val register: String?,
        val zone: Int?,
        val payload: JSONObject?,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.READ_RESULT
    }

    data class Ack(
        val msgId: String?,
        val status: MailboxAckStatus?,
        val reason: String?,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.ACK
    }

    /** Broker link state; `state` stays raw — typed mapping is B-2 scope. */
    data class Status(
        val state: String?,
        val detail: String?,
        override val raw: JSONObject,
    ) : MailboxInbound() {
        override val type: String get() = MailboxMessageType.STATUS
    }

    /** Optional recoverable protocol/client error — does not fail the socket. */
    data class Error(
        val message: String?,
        val reason: String?,
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
                MailboxMessageType.SNAPSHOT -> {
                    val (units, rawUnits) = parseUnits(json.optJSONObject("units"))
                    Snapshot(
                        units = units,
                        rawUnits = rawUnits,
                        raw = json,
                    )
                }
                MailboxMessageType.EVENT ->
                    Event(
                        unitType = json.optStringOrNull("unit_type"),
                        unitId = json.optStringOrNull("unit_id"),
                        register = json.optStringOrNull("register"),
                        zone = json.optIntOrNull("zone"),
                        payload = json.optJSONObject("payload"),
                        raw = json,
                    )
                MailboxMessageType.READ_RESULT ->
                    ReadResult(
                        msgId = json.optStringOrNull("msg_id"),
                        unitType = json.optStringOrNull("unit_type"),
                        unitId = json.optStringOrNull("unit_id"),
                        register = json.optStringOrNull("register"),
                        zone = json.optIntOrNull("zone"),
                        payload = json.optJSONObject("payload"),
                        raw = json,
                    )
                MailboxMessageType.ACK ->
                    Ack(
                        msgId = json.optStringOrNull("msg_id"),
                        status = MailboxAckStatus.fromWire(json.optStringOrNull("status")),
                        reason = json.optStringOrNull("reason"),
                        raw = json,
                    )
                MailboxMessageType.STATUS ->
                    Status(
                        state = json.optStringOrNull("state"),
                        detail = json.optStringOrNull("detail"),
                        raw = json,
                    )
                MailboxMessageType.ERROR ->
                    Error(
                        message =
                            json.optStringOrNull("message")
                                ?: json.optStringOrNull("reason"),
                        reason = json.optStringOrNull("reason"),
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

    /**
     * Write a register value; [zone] is only for zone-bearing registers (03/04)
     * and is omitted when null. Addressing fields are optional (default primary unit).
     */
    data class Write(
        override val msgId: String,
        val register: String,
        val payload: MailboxPayload,
        val unitType: String? = null,
        val unitId: String? = null,
        val zone: Int? = null,
    ) : MailboxOutbound() {
        override val type: String get() = MailboxMessageType.WRITE

        override fun toJson(): JSONObject =
            JSONObject().apply {
                put("type", type)
                put("msg_id", msgId)
                put("register", register)
                when (payload) {
                    is MailboxPayload.Typed -> put("payload", payload.payload)
                    is MailboxPayload.RawHex -> put("payload", payload.hex)
                }
                unitType?.let { put("unit_type", it) }
                unitId?.let { put("unit_id", it) }
                zone?.let { put("zone", it) }
            }
    }

    /**
     * Request a register value; optional addressing fields are omitted when null.
     */
    data class Read(
        override val msgId: String,
        val register: String,
        val unitType: String? = null,
        val unitId: String? = null,
        val zone: Int? = null,
    ) : MailboxOutbound() {
        override val type: String get() = MailboxMessageType.READ

        override fun toJson(): JSONObject =
            JSONObject().apply {
                put("type", type)
                put("msg_id", msgId)
                put("register", register)
                unitType?.let { put("unit_type", it) }
                unitId?.let { put("unit_id", it) }
                zone?.let { put("zone", it) }
            }
    }

    /** One-shot broker command ([`MailboxCommandAction`]). */
    data class Command(
        override val msgId: String,
        val action: String,
    ) : MailboxOutbound() {
        override val type: String get() = MailboxMessageType.COMMAND

        override fun toJson(): JSONObject =
            JSONObject().apply {
                put("type", type)
                put("msg_id", msgId)
                put("action", action)
            }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return value.ifEmpty { null }
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
}

private fun parseUnits(
    units: JSONObject?,
): Pair<Map<String, Map<String, JSONObject>>, Map<String, Map<String, String>>> {
    if (units == null) {
        return emptyMap<String, Map<String, JSONObject>>() to
            emptyMap<String, Map<String, String>>()
    }
    val typed = mutableMapOf<String, Map<String, JSONObject>>()
    val raw = mutableMapOf<String, Map<String, String>>()
    for (unitKey in units.keys()) {
        val registers = units.optJSONObject(unitKey) ?: continue
        val registerMap = mutableMapOf<String, JSONObject>()
        val rawRegisterMap = mutableMapOf<String, String>()
        for (register in registers.keys()) {
            // JSON type split, no reinterpretation: objects → typed, strings → raw.
            when (val value = registers.opt(register)) {
                is JSONObject -> registerMap[register] = value
                is String -> rawRegisterMap[register] = value
            }
        }
        typed[unitKey] = registerMap
        if (rawRegisterMap.isNotEmpty()) raw[unitKey] = rawRegisterMap
    }
    return typed to raw
}
