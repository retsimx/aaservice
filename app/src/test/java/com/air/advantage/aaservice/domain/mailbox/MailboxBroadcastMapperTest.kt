package com.air.advantage.aaservice.domain.mailbox

import com.air.advantage.aaservice.data.mailbox.MailboxFixtures
import com.air.advantage.aaservice.data.mailbox.MailboxInbound
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [MailboxBroadcastMapper] is pure Kotlin (no Android imports), but its `org.json.JSONObject`
 * usage needs a real JSON implementation — the plain JVM unit test sandbox stubs Android's
 * `org.json` classes to return defaults (`testOptions.unitTests.isReturnDefaultValues`), so
 * this test runs under Robolectric purely to get working JSON parsing, matching how
 * [MailboxInbound] itself is exercised in `OkHttpMailboxWsClientTest`.
 */
@RunWith(RobolectricTestRunner::class)
class MailboxBroadcastMapperTest {

    private val typeBytes = "17".toByteArray(Charsets.UTF_8)
    private val appStoreBytes = "MyAir5".toByteArray(Charsets.UTF_8)

    private fun text(payload: ByteArray): String = String(payload, Charsets.UTF_8)

    private fun map(
        inbound: MailboxInbound,
        logger: (String) -> Unit = {},
        cachedPayload: (String) -> ByteArray? = { null },
    ): List<MappedPoll> =
        MailboxBroadcastMapper.map(inbound, typeBytes, appStoreBytes, cachedPayload, logger)

    private fun event(json: JSONObject): MailboxInbound.Event =
        MailboxInbound.parse(json) as MailboxInbound.Event

    // ── snapshot → register 05 (system) ─────────────────────────

    @Test
    fun `snapshot register 05 maps to getSystemData with mapped fields and transform applied`() {
        val snapshot = MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot

        val polls = map(snapshot)
        val systemPoll = polls.single { it.tag == "getSystemData" }
        val xml = text(systemPoll.payload)

        // GetSystemDataTransformer effects: type/AppStore injected, dhcp..gateway stripped, MyAppRev overwritten.
        assertTrue("type injected", xml.contains("<type>17</type>"))
        assertTrue("AppStore injected", xml.contains("<AppStore>MyAir5</AppStore>"))
        assertTrue("MyAppRev overwritten", xml.contains("<MyAppRev>14.150</MyAppRev>"))
        assertFalse("dhcp stripped", xml.contains("<dhcp>"))
        assertFalse("gateway stripped", xml.contains("<gateway>"))

        // Mapped HVAC fields from fixture's register "05" (power/mode/fan/target_temp_c/myzone_id/fresh_air).
        assertTrue("power mapped", xml.contains("<state>on</state>"))
        assertTrue("mode mapped", xml.contains("<mode>cool</mode>"))
        assertTrue("fan mapped", xml.contains("<fan>auto</fan>"))
        assertTrue("target_temp_c mapped", xml.contains("<setTemp>22.5</setTemp>"))
        assertTrue("myzone_id mapped", xml.contains("<myZone>1</myZone>"))
        assertTrue("FAstatus mapped", xml.contains("<FAstatus>1</FAstatus>"))
    }

    // ── snapshot → zones ──────────────────────────────────────────

    @Test
    fun `snapshot zones map to getZoneData per zone id`() {
        val snapshot = MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot

        val polls = map(snapshot)
        val tags = polls.map { it.tag }
        assertTrue("zone 1 present", tags.contains("getZoneData?zone=1"))
        assertTrue("zone 2 present", tags.contains("getZoneData?zone=2"))

        val zone1 = text(polls.single { it.tag == "getZoneData?zone=1" }.payload)
        assertTrue(zone1.contains("<request>getZoneData</request>"))
        assertTrue(zone1.contains("<zone>1</zone>"))
        assertTrue("open=true maps to state on", zone1.contains("<state>on</state>"))
        assertTrue("damper_pct mapped", zone1.contains("<damper>100</damper>"))
        assertTrue("sensor_type mapped", zone1.contains("<sensor>rf</sensor>"))
        assertTrue("target_temp_c mapped", zone1.contains("<temp>22.5</temp>"))
        assertTrue("measured_temp_c mapped", zone1.contains("<measuredTemp>23.1</measuredTemp>"))

        val zone2 = text(polls.single { it.tag == "getZoneData?zone=2" }.payload)
        assertTrue("zone 2 open=false maps to state off", zone2.contains("<state>off</state>"))
    }

    // ── snapshot → rf_sys_id must not change the XML shape ───────

    @Test
    fun `snapshot rf_sys_id in register 05 does not change the getSystemData XML shape`() {
        val snapshot = MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot
        val withRfSysId = map(snapshot).single { it.tag == "getSystemData" }
        val xml = text(withRfSysId.payload)

        // rf_sys_id is parsed implicitly but has no MyAir5 XML tag — it must never surface.
        assertFalse("no rfSys tag in xml", xml.contains("rfSys"))
        assertFalse("no rf_sys_id in xml", xml.contains("rf_sys_id"))

        // The same snapshot without rf_sys_id must produce byte-identical output.
        val json = MailboxFixtures.asJson("mailbox/mailbox_snapshot.json")
        json.getJSONObject("units").getJSONObject("07:181f3").getJSONObject("05")
            .remove("rf_sys_id")
        val variant = MailboxInbound.parse(json) as MailboxInbound.Snapshot
        val variantPoll = map(variant).single { it.tag == "getSystemData" }

        assertEquals(
            "rf_sys_id must not change the broadcast payload",
            withRfSysId.payload.toList(),
            variantPoll.payload.toList(),
        )
    }

    // ── event → register 03 (zone state, fresh build, no cache) ──

    @Test
    fun `register 03 event without cache builds zone poll from event fields alone`() {
        val event = MailboxInbound.parse(MailboxFixtures.event()) as MailboxInbound.Event
        assertEquals("03", event.register)

        val polls = map(event)
        val poll = polls.single()
        assertEquals("getZoneData?zone=1", poll.tag)

        val xml = text(poll.payload)
        assertTrue(xml.contains("<zone>1</zone>"))
        assertTrue("open=true maps to state on", xml.contains("<state>on</state>"))
        assertTrue("damper_pct mapped", xml.contains("<damper>80</damper>"))
        assertTrue("measured_temp_c mapped", xml.contains("<measuredTemp>23.4</measuredTemp>"))
        // Fields absent from the sparse event payload must not be invented.
        assertFalse(xml.contains("<sensor>"))
    }

    // ── event → register 03 (merge onto cache) ───────────────────

    @Test
    fun `register 03 event merges sparse fields onto cached zone payload`() {
        val snapshot = MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot
        val cachedZone1 = map(snapshot).single { it.tag == "getZoneData?zone=1" }.payload

        val event = MailboxInbound.parse(MailboxFixtures.event()) as MailboxInbound.Event
        val merged = map(event) { tag -> if (tag == "getZoneData?zone=1") cachedZone1 else null }
            .single()
        val xml = text(merged.payload)
        assertTrue("measured_temp_c patched from event", xml.contains("<measuredTemp>23.4</measuredTemp>"))
        // ...while fields the sparse event omitted survive from the cached snapshot payload.
        assertTrue("sensor_type preserved from cache", xml.contains("<sensor>rf</sensor>"))
        assertTrue("target_temp_c preserved from cache", xml.contains("<temp>22.5</temp>"))
    }

    // ── event → register 05 (system) ──────────────────────────────

    @Test
    fun `register 05 event without cache builds getSystemData and applies transform`() {
        val event = event(
            JSONObject()
                .put("type", "event")
                .put("register", "05")
                .put(
                    "payload",
                    JSONObject().put("power", "off").put("mode", "fan"),
                ),
        )

        val poll = map(event).single()
        assertEquals("getSystemData", poll.tag)
        val xml = text(poll.payload)
        assertTrue(xml.contains("<MyAppRev>14.150</MyAppRev>"))
        assertTrue(xml.contains("<state>off</state>"))
        assertTrue(xml.contains("<mode>fan</mode>"))
    }

    @Test
    fun `register 05 event merges onto cached getSystemData payload`() {
        val snapshot = MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot
        val cachedSystem = map(snapshot).single { it.tag == "getSystemData" }.payload

        val ev = event(
            JSONObject()
                .put("type", "event")
                .put("register", "05")
                .put("payload", JSONObject().put("fan", "high")),
        )

        val merged = map(ev) { tag -> if (tag == "getSystemData") cachedSystem else null }.single()
        val xml = text(merged.payload)
        assertTrue("fan patched from event", xml.contains("<fan>high</fan>"))
        assertTrue("power preserved from cache", xml.contains("<state>on</state>"))
        assertTrue("MyAppRev untouched", xml.contains("<MyAppRev>14.150</MyAppRev>"))
    }

    // ── event → register 04 (zone limits: ignore + log) ──────────

    @Test
    fun `register 04 zone_limits event maps to empty list and invokes the injected logger`() {
        val logged = mutableListOf<String>()
        val ev = event(
            JSONObject()
                .put("type", "event")
                .put("register", "04")
                .put("zone", 1)
                .put("payload", JSONObject().put("min_damper", 0).put("max_damper", 100)),
        )

        assertTrue("zone_limits event must not broadcast", map(ev, logger = logged::add).isEmpty())
        assertTrue(
            "logger must have been invoked for the ignored zone_limits event",
            logged.isNotEmpty(),
        )
        assertTrue(
            "logger message should identify the ignored zone_limits event, got: ${logged.joinToString()}",
            logged.any { it.contains("zone_limits") && it.contains("zone 1") },
        )
    }

    // ── unknown / sparse / non-mappable inputs never throw ────────

    @Test
    fun `event with unknown register maps to empty list`() {
        val event = event(
            JSONObject()
                .put("type", "event")
                .put("register", "zone_config")
                .put("payload", JSONObject().put("total_zones", 4)),
        )

        assertTrue(map(event).isEmpty())
    }

    @Test
    fun `unknown hex register event maps to empty list`() {
        val event = event(
            JSONObject()
                .put("type", "event")
                .put("register", "06")
                .put("payload", JSONObject().put("anything", 1)),
        )

        assertTrue(map(event).isEmpty())
    }

    @Test
    fun `event with missing register or payload maps to empty list without throwing`() {
        val noRegister = event(JSONObject().put("type", "event").put("payload", JSONObject()))
        assertTrue(map(noRegister).isEmpty())

        val noPayload = event(JSONObject().put("type", "event").put("register", "05"))
        assertTrue(map(noPayload).isEmpty())
    }

    @Test
    fun `register 03 event without a zone field maps to empty list without throwing`() {
        // The zone is part of the message address; the daemon always sends it for
        // zone-bearing registers, but a missing one must not crash the mapper.
        val event = event(
            JSONObject()
                .put("type", "event")
                .put("register", "03")
                .put("payload", JSONObject().put("open", true)),
        )

        assertTrue(map(event).isEmpty())
    }

    @Test
    fun `register 03 event with non-numeric zone maps to empty list without throwing`() {
        // optInt coerces a garbage string to 0; zones are 1-based, so a zone-0 poll
        // must never be emitted (old protocol also bailed on non-numeric zone_id).
        val event = event(
            JSONObject()
                .put("type", "event")
                .put("register", "03")
                .put("zone", "x")
                .put("payload", JSONObject().put("open", true)),
        )

        assertTrue(map(event).isEmpty())
    }

    @Test
    fun `snapshot missing register 05 and zones maps to empty list`() {
        val snapshot = MailboxInbound.parse(
            JSONObject().put("type", "snapshot").put("unit_id", "AA-TEST-002"),
        ) as MailboxInbound.Snapshot

        assertTrue(map(snapshot).isEmpty())
    }

    @Test
    fun `snapshot register 04 zone_limits map produces no getZoneData polls`() {
        val snapshot = MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot

        val zonePolls = map(snapshot).filter { it.tag.startsWith("getZoneData") }

        // Only the two "03" zones (1 and 2) are emitted — the "04" nested zone-limits
        // map must be silently skipped.
        assertEquals(
            "only register 03 zones should emit getZoneData polls, got ${zonePolls.map { it.tag }}",
            listOf("getZoneData?zone=1", "getZoneData?zone=2"),
            zonePolls.map { it.tag },
        )
    }

    @Test
    fun `non-snapshot non-event inbound types map to empty list`() {
        val ack = MailboxInbound.parse(
            JSONObject().put("type", "ack").put("msg_id", "1").put("status", "success"),
        )
        val error = MailboxInbound.parse(
            JSONObject().put("type", "error").put("message", "boom"),
        )
        val unknown = MailboxInbound.parse(JSONObject().put("type", "something_else"))

        assertTrue(map(ack).isEmpty())
        assertTrue(map(error).isEmpty())
        assertTrue(map(unknown).isEmpty())
    }

    @Test
    fun `merge onto cache without matching tag falls back safely instead of throwing`() {
        val event = event(
            JSONObject()
                .put("type", "event")
                .put("register", "03")
                .put("zone", 3)
                .put("payload", JSONObject().put("open", true)),
        )

        // Cache lookup returns bytes for a completely different, malformed XML shape; merging
        // must not throw even though none of the field tags exist in it.
        val poll = map(event) { "not xml".toByteArray(Charsets.UTF_8) }.single()
        assertEquals("getZoneData?zone=3", poll.tag)
    }
}
