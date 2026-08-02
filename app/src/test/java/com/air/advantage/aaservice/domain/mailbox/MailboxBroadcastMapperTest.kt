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
        cachedPayload: (String) -> ByteArray? = { null },
    ): List<MappedPoll> =
        MailboxBroadcastMapper.map(inbound, typeBytes, appStoreBytes, cachedPayload)

    // ── snapshot → system_status ─────────────────────────────────

    @Test
    fun `snapshot system_status maps to getSystemData with mapped fields and transform applied`() {
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

        // Mapped HVAC fields from fixture's system_status (power/mode/fan/target_temp_c/myzone_id/fresh_air).
        assertTrue("power mapped", xml.contains("<state>on</state>"))
        assertTrue("mode mapped", xml.contains("<mode>cool</mode>"))
        assertTrue("fan mapped", xml.contains("<fan>auto</fan>"))
        assertTrue("target_temp_c mapped", xml.contains("<setTemp>22.5</setTemp>"))
        assertTrue("myzone_id mapped", xml.contains("<myZone>1</myZone>"))
        assertTrue("fresh_air mapped", xml.contains("<freshAir>off</freshAir>"))
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
        assertTrue("sensor_type mapped", zone1.contains("<sensor>temp</sensor>"))
        assertTrue("target_temp_c mapped", zone1.contains("<temp>22.5</temp>"))
        assertTrue("measured_temp_c mapped", zone1.contains("<measuredTemp>23.1</measuredTemp>"))

        val zone2 = text(polls.single { it.tag == "getZoneData?zone=2" }.payload)
        assertTrue("zone 2 open=false maps to state off", zone2.contains("<state>off</state>"))
    }

    // ── event → zone_state (fresh build, no cache) ───────────────

    @Test
    fun `zone_state event without cache builds zone poll from event fields alone`() {
        val event = MailboxInbound.parse(MailboxFixtures.event()) as MailboxInbound.Event
        assertEquals("zone_state", event.register)

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

    // ── event → zone_state (merge onto cache) ────────────────────

    @Test
    fun `zone_state event merges sparse fields onto cached zone payload`() {
        val snapshot = MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot
        val cachedZone1 = map(snapshot).single { it.tag == "getZoneData?zone=1" }.payload

        val event = MailboxInbound.parse(MailboxFixtures.event()) as MailboxInbound.Event
        val merged = map(event) { tag -> if (tag == "getZoneData?zone=1") cachedZone1 else null }
            .single()
        val xml = text(merged.payload)

        // Event patches these fields...
        assertTrue("damper_pct patched from event", xml.contains("<damper>80</damper>"))
        assertTrue("measured_temp_c patched from event", xml.contains("<measuredTemp>23.4</measuredTemp>"))
        // ...while fields the sparse event omitted survive from the cached snapshot payload.
        assertTrue("sensor_type preserved from cache", xml.contains("<sensor>temp</sensor>"))
        assertTrue("target_temp_c preserved from cache", xml.contains("<temp>22.5</temp>"))
    }

    // ── event → system_status ─────────────────────────────────────

    @Test
    fun `system_status event without cache builds getSystemData and applies transform`() {
        val event = MailboxInbound.parse(
            JSONObject()
                .put("type", "mailbox_event")
                .put("register", "system_status")
                .put(
                    "payload",
                    JSONObject().put("power", "off").put("mode", "fan"),
                ),
        ) as MailboxInbound.Event

        val poll = map(event).single()
        assertEquals("getSystemData", poll.tag)
        val xml = text(poll.payload)
        assertTrue(xml.contains("<MyAppRev>14.150</MyAppRev>"))
        assertTrue(xml.contains("<state>off</state>"))
        assertTrue(xml.contains("<mode>fan</mode>"))
    }

    @Test
    fun `system_status event merges onto cached getSystemData payload`() {
        val snapshot = MailboxInbound.parse(MailboxFixtures.snapshot()) as MailboxInbound.Snapshot
        val cachedSystem = map(snapshot).single { it.tag == "getSystemData" }.payload

        val event = MailboxInbound.parse(
            JSONObject()
                .put("type", "mailbox_event")
                .put("register", "system_status")
                .put("payload", JSONObject().put("fan", "high")),
        ) as MailboxInbound.Event

        val merged = map(event) { tag -> if (tag == "getSystemData") cachedSystem else null }.single()
        val xml = text(merged.payload)
        assertTrue("fan patched from event", xml.contains("<fan>high</fan>"))
        assertTrue("power preserved from cache", xml.contains("<state>on</state>"))
        assertTrue("MyAppRev untouched", xml.contains("<MyAppRev>14.150</MyAppRev>"))
    }

    // ── unknown / sparse / non-mappable inputs never throw ────────

    @Test
    fun `event with unknown register maps to empty list`() {
        val event = MailboxInbound.parse(
            JSONObject()
                .put("type", "mailbox_event")
                .put("register", "zone_config")
                .put("payload", JSONObject().put("total_zones", 4)),
        ) as MailboxInbound.Event

        assertTrue(map(event).isEmpty())
    }

    @Test
    fun `event with missing register or payload maps to empty list without throwing`() {
        val noRegister = MailboxInbound.parse(
            JSONObject().put("type", "mailbox_event").put("payload", JSONObject()),
        ) as MailboxInbound.Event
        assertTrue(map(noRegister).isEmpty())

        val noPayload = MailboxInbound.parse(
            JSONObject().put("type", "mailbox_event").put("register", "system_status"),
        ) as MailboxInbound.Event
        assertTrue(map(noPayload).isEmpty())
    }

    @Test
    fun `zone_state event with non-numeric zone_id maps to empty list without throwing`() {
        val event = MailboxInbound.parse(
            JSONObject()
                .put("type", "mailbox_event")
                .put("register", "zone_state")
                .put("payload", JSONObject().put("zone_id", "not-a-number").put("open", true)),
        ) as MailboxInbound.Event

        assertTrue(map(event).isEmpty())
    }

    @Test
    fun `snapshot missing system_status and zones maps to empty list`() {
        val snapshot = MailboxInbound.parse(
            JSONObject().put("type", "mailbox_snapshot").put("unit_id", "AA-TEST-002"),
        ) as MailboxInbound.Snapshot

        assertTrue(map(snapshot).isEmpty())
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
        val event = MailboxInbound.parse(
            JSONObject()
                .put("type", "mailbox_event")
                .put("register", "zone_state")
                .put("payload", JSONObject().put("zone_id", "3").put("open", true)),
        ) as MailboxInbound.Event

        // Cache lookup returns bytes for a completely different, malformed XML shape; merging
        // must not throw even though none of the field tags exist in it.
        val poll = map(event) { "not xml".toByteArray(Charsets.UTF_8) }.single()
        assertEquals("getZoneData?zone=3", poll.tag)
    }
}
