package com.air.advantage.aaservice.data.mailbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden parse/serialize tests for every mailbox message type (cb-daemon wire
 * contract: `type` tag + snake_case keys). Pure `org.json` — Robolectric only
 * because the plain JVM test sandbox stubs Android's `org.json` classes
 * (`isReturnDefaultValues`), matching the other mailbox tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MailboxMessageTest {

    // ── inbound: snapshot ────────────────────────────────────────

    @Test
    fun `snapshot parses units keyed unit_type colon unit_id to register payloads`() {
        val msg = MailboxInbound.parse(
            """
            {
              "type": "snapshot",
              "units": {
                "aircon:1": {
                  "system_status": {
                    "power": "on",
                    "mode": "cool",
                    "myzone_id": 1
                  },
                  "zones": {
                    "1": {
                      "open": true,
                      "damper_pct": 100
                    }
                  }
                },
                "aircon:2": {
                  "system_status": {
                    "power": "off"
                  }
                }
              }
            }
            """.trimIndent(),
        )
        assertTrue(msg is MailboxInbound.Snapshot)
        val snapshot = msg as MailboxInbound.Snapshot
        assertEquals(2, snapshot.units.size)

        val unit1 = snapshot.units["aircon:1"]
        assertNotNull(unit1)
        val status = unit1!!["system_status"]
        assertEquals("on", status!!.getString("power"))
        assertEquals(1, status.getInt("myzone_id"))
        assertEquals(100, unit1["zones"]!!.getJSONObject("1").getInt("damper_pct"))

        val unit2 = snapshot.units["aircon:2"]
        assertEquals("off", unit2!!["system_status"]!!.getString("power"))
        assertEquals(MailboxMessageType.SNAPSHOT, snapshot.type)
    }

    @Test
    fun `snapshot without units parses to empty map`() {
        val snapshot = MailboxInbound.parse(
            """{"type": "snapshot"}""",
        ) as MailboxInbound.Snapshot
        assertTrue(snapshot.units.isEmpty())
    }

    // ── inbound: event ───────────────────────────────────────────

    @Test
    fun `event parses addressing fields and zone payload`() {
        val msg = MailboxInbound.parse(
            """
            {
              "type": "event",
              "unit_type": "aircon",
              "unit_id": "1",
              "register": "zone_state",
              "zone": 3,
              "payload": {
                "open": true,
                "damper_pct": 80
              }
            }
            """.trimIndent(),
        )
        assertTrue(msg is MailboxInbound.Event)
        val event = msg as MailboxInbound.Event
        assertEquals("aircon", event.unitType)
        assertEquals("1", event.unitId)
        assertEquals("zone_state", event.register)
        assertEquals(3, event.zone)
        assertEquals(80, event.payload!!.getInt("damper_pct"))
        assertEquals(MailboxMessageType.EVENT, event.type)
    }

    @Test
    fun `event without zone parses with null zone and null addressing`() {
        val event = MailboxInbound.parse(
            """
            {
              "type": "event",
              "register": "system_status",
              "payload": {
                "power": "on"
              }
            }
            """.trimIndent(),
        ) as MailboxInbound.Event
        assertEquals("system_status", event.register)
        assertNull(event.zone)
        assertNull(event.unitType)
        assertNull(event.unitId)
        assertEquals("on", event.payload!!.getString("power"))
    }

    @Test
    fun `event with raw hex string payload parses payload null and keeps string on raw`() {
        val event = MailboxInbound.parse(
            """
            {
              "type": "event",
              "register": "sensor_pairing",
              "payload": "0703181f30a00000000000000"
            }
            """.trimIndent(),
        ) as MailboxInbound.Event
        assertNull(event.payload)
        assertEquals("0703181f30a00000000000000", event.raw.getString("payload"))
    }

    // ── inbound: read_result ─────────────────────────────────────

    @Test
    fun `read_result parses msg_id addressing and payload`() {
        val msg = MailboxInbound.parse(
            """
            {
              "type": "read_result",
              "msg_id": "r1",
              "unit_type": "aircon",
              "unit_id": "1",
              "register": "zone_limits",
              "zone": 2,
              "payload": {
                "min_damper": 0,
                "max_damper": 100
              }
            }
            """.trimIndent(),
        )
        assertTrue(msg is MailboxInbound.ReadResult)
        val result = msg as MailboxInbound.ReadResult
        assertEquals("r1", result.msgId)
        assertEquals("aircon", result.unitType)
        assertEquals("1", result.unitId)
        assertEquals("zone_limits", result.register)
        assertEquals(2, result.zone)
        assertEquals(0, result.payload!!.getInt("min_damper"))
        assertEquals(MailboxMessageType.READ_RESULT, result.type)
    }

    // ── inbound: ack ─────────────────────────────────────────────

    private fun ackJson(status: String, reason: String?): JSONObject =
        JSONObject().apply {
            put("type", "ack")
            put("msg_id", "a1")
            put("status", status)
            reason?.let { put("reason", it) }
        }

    @Test
    fun `ack success parses status with and without reason`() {
        val withReason = MailboxInbound.parse(
            ackJson("success", "delivered"),
        ) as MailboxInbound.Ack
        assertEquals("a1", withReason.msgId)
        assertEquals(MailboxAckStatus.SUCCESS, withReason.status)
        assertEquals("delivered", withReason.reason)

        val withoutReason = MailboxInbound.parse(
            ackJson("success", null),
        ) as MailboxInbound.Ack
        assertEquals(MailboxAckStatus.SUCCESS, withoutReason.status)
        assertNull(withoutReason.reason)
        assertEquals(MailboxMessageType.ACK, withoutReason.type)
    }

    @Test
    fun `ack error parses status with and without reason`() {
        val withReason = MailboxInbound.parse(
            ackJson("error", "register write rejected"),
        ) as MailboxInbound.Ack
        assertEquals(MailboxAckStatus.ERROR, withReason.status)
        assertEquals("register write rejected", withReason.reason)

        val withoutReason = MailboxInbound.parse(
            ackJson("error", null),
        ) as MailboxInbound.Ack
        assertEquals(MailboxAckStatus.ERROR, withoutReason.status)
        assertNull(withoutReason.reason)
    }

    @Test
    fun `ack with unknown status parses status null`() {
        val ack = MailboxInbound.parse(
            ackJson("bogus", null),
        ) as MailboxInbound.Ack
        assertNull(ack.status)
    }

    // ── inbound: status ──────────────────────────────────────────

    @Test
    fun `status parses state with and without detail`() {
        val withDetail = MailboxInbound.parse(
            JSONObject()
                .put("type", "status")
                .put("state", "synced")
                .put("detail", "3 units"),
        ) as MailboxInbound.Status
        assertEquals("synced", withDetail.state)
        assertEquals("3 units", withDetail.detail)

        val bare = MailboxInbound.parse(
            JSONObject().put("type", "status").put("state", "link_down"),
        ) as MailboxInbound.Status
        assertEquals("link_down", bare.state)
        assertNull(bare.detail)
        assertEquals(MailboxMessageType.STATUS, bare.type)
    }

    // ── inbound: error ───────────────────────────────────────────

    @Test
    fun `error parses message and reason with reason fallback`() {
        val full = MailboxInbound.parse(
            JSONObject()
                .put("type", "error")
                .put("message", "malformed payload")
                .put("reason", "ignored"),
        ) as MailboxInbound.Error
        assertEquals("malformed payload", full.message)
        assertEquals("ignored", full.reason)

        val reasonOnly = MailboxInbound.parse(
            JSONObject().put("type", "error").put("reason", "denied"),
        ) as MailboxInbound.Error
        assertEquals("denied", reasonOnly.message)
        assertEquals("denied", reasonOnly.reason)
        assertEquals(MailboxMessageType.ERROR, reasonOnly.type)
    }

    // ── inbound: unknown ─────────────────────────────────────────

    @Test
    fun `unknown type parses to Unknown with type and raw preserved`() {
        val msg = MailboxInbound.parse(
            """{"type": "daemon_ping", "seq": 1}""",
        )
        assertTrue(msg is MailboxInbound.Unknown)
        val unknown = msg as MailboxInbound.Unknown
        assertEquals("daemon_ping", unknown.type)
        assertEquals(1, unknown.raw.getInt("seq"))
    }

    // ── ack status wire mapping ──────────────────────────────────

    @Test
    fun `ack status wire mapping round trips`() {
        assertEquals(MailboxAckStatus.SUCCESS, MailboxAckStatus.fromWire("success"))
        assertEquals(MailboxAckStatus.ERROR, MailboxAckStatus.fromWire("error"))
        assertNull(MailboxAckStatus.fromWire("bogus"))
        assertNull(MailboxAckStatus.fromWire(null))
        assertEquals("success", MailboxAckStatus.SUCCESS.toWire())
        assertEquals("error", MailboxAckStatus.ERROR.toWire())
    }

    // ── outbound: write ──────────────────────────────────────────

    @Test
    fun `write with typed payload serializes type msg_id register and payload object`() {
        val frame = MailboxOutbound.Write(
            msgId = "w1",
            register = "system_status",
            payload = MailboxPayload.Typed(
                JSONObject().put("power", "on").put("mode", "cool"),
            ),
        )
        val json = JSONObject(frame.toJsonString())
        assertEquals("write", json.getString("type"))
        assertEquals("w1", json.getString("msg_id"))
        assertEquals("system_status", json.getString("register"))
        assertEquals("on", json.getJSONObject("payload").getString("power"))
        // Absent addressing fields must not appear.
        assertFalse(json.has("unit_type"))
        assertFalse(json.has("unit_id"))
        assertFalse(json.has("zone"))
    }

    @Test
    fun `write with raw hex payload serializes payload as plain string`() {
        val frame = MailboxOutbound.Write(
            msgId = "w2",
            register = "sensor_pairing",
            payload = MailboxPayload.RawHex("0703181f30a00000000000000"),
        )
        val json = JSONObject(frame.toJsonString())
        assertEquals("0703181f30a00000000000000", json.getString("payload"))
        assertTrue(json.get("payload") is String)
    }

    @Test
    fun `write with unit_type unit_id and zone serializes addressing fields`() {
        val frame = MailboxOutbound.Write(
            msgId = "w3",
            register = "zone_state",
            payload = MailboxPayload.Typed(JSONObject().put("open", true)),
            unitType = "aircon",
            unitId = "1",
            zone = 3,
        )
        val json = JSONObject(frame.toJsonString())
        assertEquals("aircon", json.getString("unit_type"))
        assertEquals("1", json.getString("unit_id"))
        assertEquals(3, json.getInt("zone"))
        assertEquals("w3", json.getString("msg_id"))
    }

    // ── outbound: read ───────────────────────────────────────────

    @Test
    fun `read serializes type msg_id register and omits absent addressing`() {
        val frame = MailboxOutbound.Read(msgId = "r1", register = "zone_limits")
        val json = JSONObject(frame.toJsonString())
        assertEquals("read", json.getString("type"))
        assertEquals("r1", json.getString("msg_id"))
        assertEquals("zone_limits", json.getString("register"))
        assertFalse(json.has("unit_type"))
        assertFalse(json.has("unit_id"))
        assertFalse(json.has("zone"))
    }

    @Test
    fun `read with unit_type unit_id and zone serializes addressing fields`() {
        val frame = MailboxOutbound.Read(
            msgId = "r2",
            register = "zone_state",
            unitType = "aircon",
            unitId = "1",
            zone = 2,
        )
        val json = JSONObject(frame.toJsonString())
        assertEquals("aircon", json.getString("unit_type"))
        assertEquals("1", json.getString("unit_id"))
        assertEquals(2, json.getInt("zone"))
    }

    // ── outbound: command ────────────────────────────────────────

    @Test
    fun `command resync serializes type msg_id and action`() {
        val frame = MailboxOutbound.Command(
            msgId = "c1",
            action = MailboxCommandAction.RESYNC,
        )
        val json = JSONObject(frame.toJsonString())
        assertEquals("command", json.getString("type"))
        assertEquals("c1", json.getString("msg_id"))
        assertEquals("resync", json.getString("action"))
    }

    @Test
    fun `command flush_unit serializes action`() {
        val frame = MailboxOutbound.Command(
            msgId = "c2",
            action = MailboxCommandAction.FLUSH_UNIT,
        )
        val json = JSONObject(frame.toJsonString())
        assertEquals("command", json.getString("type"))
        assertEquals("flush_unit", json.getString("action"))
    }
}
