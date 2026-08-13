package com.air.advantage.aaservice.domain.mailbox

import com.air.advantage.aaservice.data.mailbox.MailboxFixtures
import com.air.advantage.aaservice.data.mailbox.MailboxInbound
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MailboxRawCanEncoderTest {

    @Test
    fun `reg 01 zone config encodes header total constant ids and filter byte`() {
        val event = event(
            "01",
            """{ "header": 32, "total_zones": 4, "constant_zones": 1, "constant_zone_ids": [1, 0, 0], "filter_clean_required": false }""",
        )
        assertEquals("getCAN 1 0703181f30120040101000000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 02 unit activation encodes brand activation and dict firmware`() {
        val event = event(
            "02",
            """{ "unit_type": "daikin", "activation_status": "code_enabled", "dict_fw_major": 2, "dict_fw_minor": 3 }""",
        )
        assertEquals("getCAN 1 0703181f30211010203000000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 03 zone state encodes open pct sensor and temps`() {
        val event = event(
            "03",
            """{ "open": true, "damper_pct": 100, "sensor_type": "wired", "target_temp_c": 22.0, "measured_temp_c": 20.5 }""",
            zone = 1,
        )
        assertEquals("getCAN 1 0703181f30301e4022c140500", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 04 zone limits encodes min max motion and rssi`() {
        val event = event(
            "04",
            """{ "min_damper": 20, "max_damper": 80, "motion_status": 2, "motion_config": 1, "zone_error": 3, "rssi": 42 }""",
            zone = 1,
        )
        assertEquals("getCAN 1 0703181f3040114500201032a", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 05 system status encodes power mode fan fresh air`() {
        val event = event(
            "05",
            """{ "power": "on", "mode": "cool", "fan": "auto", "target_temp_c": 22.0, "myzone_id": 1, "fresh_air": "off", "rf_sys_id": 0 }""",
        )
        assertEquals("getCAN 1 0703181f3050101042c010100", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 05 fresh air none encodes byte 00 not 01`() {
        val event = event(
            "05",
            """{ "power": "on", "mode": "cool", "fan": "auto", "target_temp_c": 22.0, "myzone_id": 1, "fresh_air": "none", "rf_sys_id": 0 }""",
        )
        assertEquals("getCAN 1 0703181f3050101042c010000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 06 firmware encodes fw and cb type`() {
        val event = event("06", """{ "fw_major": 5, "fw_minor": 3, "cb_type": 4, "rf_fw_major": 3 }""")
        assertEquals("getCAN 1 0703181f30605030403000000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    /**
     * Documented cb-daemon wire examples (issue #80 / cb-daemon docs) that must
     * stay pinned byte-exact against encoder output:
     *  - reg-05 system status: `0703<uid>0501010330000100` (uid placeholder)
     *  - reg-06 firmware flush token: `0701000000600000000000000`
     * Pinned below by the two following tests. Note the flush token uses dest
     * `01` while the encoder hardcodes dest `03` (tablet), so the encoded flush
     * record is `0703000000600000000000000` — identical in every byte except dest.
     */

    @Test
    fun `reg 05 documented system status example encodes byte-exact`() {
        // Documented example data `01010330000100` = power on(01), mode cool(01),
        // fan high(03), set_temp_x2 0x30 (24.0 C), myzone_id 0, fresh_air off(01),
        // rf_sys_id 0 — per the encoder's `[power][mode][fan][set_temp_x2][myzone][fresh][rf_sys]`
        // layout. Encodes as the documented record `0703<181f3>0501010330000100`.
        val event = event(
            "05",
            """{ "power": "on", "mode": "cool", "fan": "high", "target_temp_c": 24.0, "myzone_id": 0, "fresh_air": "off", "rf_sys_id": 0 }""",
        )
        assertEquals("getCAN 1 0703181f30501010330000100", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 06 flush token input encodes byte-exact record`() {
        // The documented cb-daemon flush token `0701000000600000000000000` is
        // type 07, dest 01, uid 00000, reg 06, all-zero firmware data (0/0/0/0).
        // The encoder hardcodes dest 03 (tablet) and there is no full-record
        // passthrough, so the flush input encodes as `0703000000600000000000000`:
        // identical to the documented token in every byte except dest 01 -> 03.
        val event = event(
            "06",
            """{ "fw_major": 0, "fw_minor": 0, "cb_type": 0, "rf_fw_major": 0 }""",
            unitId = "00000",
        )
        val record = MailboxRawCanEncoder.encodeEventToCan(event)!!.removePrefix("getCAN 1 ")
        assertEquals("0703000000600000000000000", record)
        // Pin the documented token byte-exact: the only delta is the dest byte.
        assertEquals("0701000000600000000000000".replaceRange(2, 4, "03"), record)
    }

    @Test
    fun `reg 08 system error NUL pads error code to 5 ASCII`() {
        val event = event("08", """{ "error_code": "AA1" }""")
        assertEquals("getCAN 1 0703181f30841413100000000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 09 activation code encodes action hex code and days`() {
        val event = event(
            "09",
            """{ "action": "set_code", "unlock_code": "A1B2", "activation_days": 43 }""",
        )
        assertEquals("getCAN 1 0703181f30901a1b22b000000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 0a unit announcement encodes all zeros`() {
        val event = event("0a", """{}""")
        assertEquals("getCAN 1 0703181f30a00000000000000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 12 sensor pairing encodes uid info byte and rev`() {
        val event = event(
            "12",
            """{ "sensor_uid": "aabbcc", "pairing": true, "sensor_rev": 3 }""",
        )
        assertEquals("getCAN 1 0703181f312aabbcc40030000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 13 info byte encodes info byte`() {
        val event = event("13", """{ "info_byte": 127 }""")
        assertEquals("getCAN 1 0703181f3137f000000000000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 26 rf device pairing encodes control type and channel`() {
        val event = event("26", """{ "pairing_control": 1, "rf_device_type": 129, "zone_channel": 3 }""")
        assertEquals("getCAN 1 0703181f32601810300000000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `reg 27 rf device calibration encodes calibration bytes`() {
        val event = event("27", """{ "calibration_control": 2, "channel": 10, "up_down_position": 5 }""")
        assertEquals("getCAN 1 0703181f327020a0500000000", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `snapshot encodes full register bank across units`() {
        val snapshot = snapshot(
            """
            {
              "type": "snapshot",
              "units": {
                "07:181f3": {
                  "05": { "power": "on", "mode": "cool", "fan": "auto", "target_temp_c": 22.0, "myzone_id": 1, "fresh_air": "off", "rf_sys_id": 0 },
                  "0a": {},
                  "03": {
                    "2": { "open": false, "damper_pct": 50, "sensor_type": "rf", "target_temp_c": 21.0, "measured_temp_c": 20.0 },
                    "1": { "open": true, "damper_pct": 100, "sensor_type": "wired", "target_temp_c": 22.0, "measured_temp_c": 20.5 }
                  },
                  "04": { "1": { "min_damper": 20, "max_damper": 80, "motion_status": 2, "motion_config": 1, "zone_error": 3, "rssi": 42 } }
                },
                "08:181f3": {
                  "12": { "sensor_uid": "aabbcc", "pairing": true, "sensor_rev": 3 }
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(
            "getCAN 1 " +
                "0703181f30301e4022c140500 " +
                "0703181f3030232012a140000 " +
                "0703181f3040114500201032a " +
                "0703181f3050101042c010100 " +
                "0703181f30a00000000000000 " +
                "0803181f312aabbcc40030000",
            MailboxRawCanEncoder.encodeGetCan(snapshot),
        )
    }

    @Test
    fun `event encodes single record delta`() {
        val event = event(
            "05",
            """{ "power": "on", "mode": "cool", "fan": "auto", "target_temp_c": 22.0, "myzone_id": 1, "fresh_air": "off", "rf_sys_id": 0 }""",
        )
        assertEquals("getCAN 1 0703181f3050101042c010100", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `event passes unknown register raw hex through verbatim`() {
        val event = event("16", "\"aabbccddeeff00\"")
        assertEquals("getCAN 1 0703181f316aabbccddeeff00", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `event passes unknown register raw hex for register ff`() {
        val event = event("ff", "\"0123456789abcd\"")
        assertEquals("getCAN 1 0703181f3ff0123456789abcd", MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `empty snapshot returns null`() {
        val snapshot = snapshot("""{ "type": "snapshot", "units": {} }""")
        assertNull(MailboxRawCanEncoder.encodeGetCan(snapshot))
    }

    @Test
    fun `snapshot with only unknown register objects returns null`() {
        val snapshot = snapshot(
            """{ "type": "snapshot", "units": { "07:181f3": { "ff": { "anything": 1 } } } }""",
        )
        assertNull(MailboxRawCanEncoder.encodeGetCan(snapshot))
    }

    @Test
    fun `encodeGetCan emits passthrough record for unknown register raw hex`() {
        val snapshot = snapshot(MailboxFixtures.snapshotRawHex())
        val can = MailboxRawCanEncoder.encodeGetCan(snapshot)
        val tokens = can!!.split(' ')
        assertEquals("getCAN", tokens[0])
        assertEquals("1", tokens[1])
        assertEquals(
            listOf(
                "0703181f30301e4012d170100",
                "0703181f3050101042d010100",
                "0703181f30faabbccddeeff00",
            ),
            tokens.drop(2),
        )
    }

    @Test
    fun `encodeGetCan keeps typed record when rawUnits also present for same register`() {
        val snapshot = MailboxInbound.Snapshot(
            units = mapOf(
                "07:181f3" to mapOf(
                    "05" to JSONObject(
                        """{ "power": "on", "mode": "cool", "fan": "auto", "target_temp_c": 22.0, "myzone_id": 1, "fresh_air": "off", "rf_sys_id": 0 }""",
                    ),
                ),
            ),
            rawUnits = mapOf(
                "07:181f3" to mapOf("05" to "deadbeefdeadbe"),
            ),
            raw = JSONObject(),
        )
        assertEquals(
            "getCAN 1 0703181f3050101042c010100",
            MailboxRawCanEncoder.encodeGetCan(snapshot),
        )
    }

    @Test
    fun `encodeGetCan falls back to raw hex when typed encode returns null`() {
        val snapshot = MailboxInbound.Snapshot(
            units = mapOf(
                "07:181f3" to mapOf(
                    "09" to JSONObject("""{ "action": "bogus", "unlock_code": "A1B2" }"""),
                ),
            ),
            rawUnits = mapOf(
                "07:181f3" to mapOf("09" to "aabbccddeeff00"),
            ),
            raw = JSONObject(),
        )
        assertEquals(
            "getCAN 1 0703181f309aabbccddeeff00",
            MailboxRawCanEncoder.encodeGetCan(snapshot),
        )
    }

    @Test
    fun `encodeGetCan ignores raw hex on zone bearing register`() {
        val snapshot = MailboxInbound.Snapshot(
            units = emptyMap(),
            rawUnits = mapOf(
                "07:181f3" to mapOf("03" to "aabbccddeeff00"),
            ),
            raw = JSONObject(),
        )
        assertNull(MailboxRawCanEncoder.encodeGetCan(snapshot))
    }

    @Test
    fun `event without unit id returns null`() {
        val event = event("05", """{ "power": "on" }""", unitId = null)
        assertNull(MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `event with null payload and no usable raw hex returns null`() {
        val event = event("16", "\"not-hex\"")
        assertNull(MailboxRawCanEncoder.encodeEventToCan(event))
    }

    @Test
    fun `read result encodes typed payload as single getCAN record`() {
        val result = readResult(
            "05",
            """{ "power": "on", "mode": "cool", "fan": "auto", "target_temp_c": 22.0, "myzone_id": 1, "fresh_air": "off", "rf_sys_id": 0 }""",
        )
        assertEquals("getCAN 1 0703181f3050101042c010100", MailboxRawCanEncoder.encodeReadResultToCan(result))
    }

    @Test
    fun `read result passes raw hex payload through verbatim`() {
        val result = readResult("16", "\"aabbccddeeff00\"")
        assertEquals("getCAN 1 0703181f316aabbccddeeff00", MailboxRawCanEncoder.encodeReadResultToCan(result))
    }

    @Test
    fun `read result without unit id returns null`() {
        val result = readResult("05", """{ "power": "on" }""", unitId = null)
        assertNull(MailboxRawCanEncoder.encodeReadResultToCan(result))
    }

    private fun event(
        register: String,
        payload: String,
        zone: Int? = null,
        unitId: String? = "181f3",
    ): MailboxInbound.Event {
        val json = JSONObject().apply {
            put("type", "event")
            put("unit_type", "07")
            unitId?.let { put("unit_id", it) }
            put("register", register)
            zone?.let { put("zone", it) }
            if (payload.startsWith('{')) {
                put("payload", JSONObject(payload))
            } else {
                put("payload", payload.trim('"'))
            }
        }
        return MailboxInbound.parse(json) as MailboxInbound.Event
    }

    private fun snapshot(json: String): MailboxInbound.Snapshot =
        MailboxInbound.parse(JSONObject(json)) as MailboxInbound.Snapshot

    private fun readResult(
        register: String,
        payload: String,
        unitId: String? = "181f3",
    ): MailboxInbound.ReadResult {
        val json = JSONObject().apply {
            put("type", "read_result")
            put("msg_id", "r1")
            put("unit_type", "07")
            unitId?.let { put("unit_id", it) }
            put("register", register)
            if (payload.startsWith('{')) {
                put("payload", JSONObject(payload))
            } else {
                put("payload", payload.trim('"'))
            }
        }
        return MailboxInbound.parse(json) as MailboxInbound.ReadResult
    }
}
