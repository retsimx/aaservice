package com.air.advantage.aaservice.domain.mailbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MailboxRawCanEncoderTest {

    @Test
    fun `snapshot can_records preferred over dto rebuild`() {
        val snap = JSONObject(
            """
            {
              "type": "mailbox_snapshot",
              "unit_id": "181f3",
              "system_status": {
                "power": "on",
                "mode": "cool",
                "fan": "auto",
                "target_temp_c": 22.0,
                "myzone_id": 1,
                "fresh_air": false
              },
              "can_records": [
                "0703181f30a00000000000000",
                "0703181f3050101032c010000",
                "0703181f30301e4012c140500"
              ]
            }
            """.trimIndent(),
        )
        val raw = MailboxRawCanEncoder.encodeGetCan(snap)
        assertEquals(
            "getCAN 1 0703181f30a00000000000000 0703181f3050101032c010000 0703181f30301e4012c140500",
            raw,
        )
    }

    @Test
    fun `snapshot encodes getCAN with system_status and zones`() {
        val snap = JSONObject(
            """
            {
              "type": "mailbox_snapshot",
              "unit_id": "181f3",
              "system_status": {
                "power": "on",
                "mode": "cool",
                "fan": "auto",
                "target_temp_c": 22.0,
                "myzone_id": 1,
                "fresh_air": false
              },
              "zones": {
                "1": {
                  "open": true,
                  "damper_pct": 100,
                  "sensor_type": "temp",
                  "target_temp_c": 22.0,
                  "measured_temp_c": 20.5
                }
              }
            }
            """.trimIndent(),
        )

        val raw = MailboxRawCanEncoder.encodeGetCan(snap)
        assertNotNull(raw)
        assertTrue(raw!!.startsWith("getCAN 1 "))
        val parts = raw.split(" ")
        assertEquals("getCAN", parts[0])
        assertEquals("1", parts[1])
        // system_status reg 05 + zone 1 reg 03
        assertEquals(2, parts.size - 2)
        for (rec in parts.drop(2)) {
            assertEquals(25, rec.length)
            assertTrue(rec.startsWith("0703181f3"))
        }
        assertTrue(parts.any { it.startsWith("0703181f305") })
        assertTrue(parts.any { it.startsWith("0703181f30301") })
    }
}
