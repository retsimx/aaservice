package com.air.advantage.aaservice.data.mailbox

import org.json.JSONObject
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Loads embedded cb-daemon D7-shaped mailbox JSON fixtures from test resources. */
object MailboxFixtures {
    fun snapshot(): String = load("mailbox/mailbox_snapshot.json")

    /** Snapshot containing a raw-hex string register (unknown register passthrough). */
    fun snapshotRawHex(): String = load("mailbox/mailbox_snapshot_rawhex.json")

    fun event(): String = load("mailbox/mailbox_event.json")

    fun ackSuccess(msgId: String): String = load("mailbox/ack_success.json").replace("__MSG_ID__", msgId)

    fun ackError(msgId: String): String = load("mailbox/ack_error.json").replace("__MSG_ID__", msgId)

    fun readResult(
        msgId: String,
        register: String,
    ): String =
        load("mailbox/read_result.json")
            .replace("__MSG_ID__", msgId)
            .replace("__REGISTER__", register)

    fun protocolError(): String = load("mailbox/protocol_error.json")

    fun unknownType(): String = load("mailbox/unknown_type.json")

    fun load(resourcePath: String): String {
        val stream =
            checkNotNull(
                MailboxFixtures::class.java.classLoader!!.getResourceAsStream(resourcePath),
            ) { "Missing fixture: $resourcePath" }
        return stream.use { InputStreamReader(it, StandardCharsets.UTF_8).readText().trim() }
    }

    fun asJson(resourcePath: String): JSONObject = JSONObject(load(resourcePath))
}
