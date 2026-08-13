package com.air.advantage.aaservice.domain.mailbox

import com.air.advantage.aaservice.data.mailbox.MailboxInbound
import com.air.advantage.aaservice.data.protocol.FrameParser
import com.air.advantage.aaservice.domain.transform.GetSystemDataTransformer
import com.air.advantage.aaservice.util.HardwareDetector
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Maps mailbox `snapshot` / `event` frames onto the same MyAir5 poll-tag
 * `MESSAGE_FROM_CB` shape the USB path produces ([UartDispatchEngine][com.air.advantage.aaservice.domain.state.UartDispatchEngine]),
 * so the existing `broadcastData` / `DataCacheRepository` / `GetSystemDataTransformer`
 * pipeline can stay unchanged (design doc `41-mailbox-to-message-from-cb.md`).
 *
 * **Best-effort, secondary to rawCan.** This mapped XML is broadcast as a best-effort
 * companion channel and is SECONDARY to the `MESSAGE_FROM_CB_SECURE` rawCan channel —
 * MyAir5 fills its `:2025` aircons from secure rawCan, not from this XML. Verified
 * against the real consumer (the MyAir5 tablet app, `com.air.advantage.myair5`,
 * decompiled): the mapped XML is rejected before parsing by
 * `com.air.advantage.uart.Xml2JsonFunctions`, which requires the stock `iZS10.3` root
 * start/stop tags in every `MESSAGE_FROM_CB` payload ("XML failed - no start and stop
 * tags"); additionally `parseXmlToData` requires mandatory
 * `name` / `zoneStationHasUnitControl` / `airconOnOff` / `fanSpeed` / numeric `mode` /
 * `centralDesiredTemp` / `numberOfZones` / `zsConstantZone1..3` and `parseZoneXml`
 * requires mandatory `name` — none of which this mapper emits. Zone `name` has no
 * register representation, so stock-shaped XML cannot be synthesized from daemon data.
 * Consequently this XML is inert for the consumer (rejected → discarded) and must not
 * be claimed as MyAir5-compatible. The path is scheduled for REMOVAL in issue B-11
 * (retsimx/aaservice#93, blocked by B-10 #91) — the rawCan-only inbound contract.
 *
 * Pure Kotlin — no Android framework imports. `org.json.JSONObject` is used because
 * [MailboxInbound] already parses frames with it (see `MailboxMessage.kt`).
 */
object MailboxBroadcastMapper {

    const val SYSTEM_TAG: String = "getSystemData"
    private const val SYSTEM_STATUS_REGISTER = "05"
    private const val ZONE_STATE_REGISTER = "03"
    private const val ZONE_LIMITS_REGISTER = "04"

    private val parser = FrameParser()

    /**
     * @param cachedPayload looks up the last broadcast payload for a poll tag (e.g. from
     *   `DataCacheRepository`), used to merge sparse [MailboxInbound.Event] fields onto the
     *   existing XML rather than rebuilding from scratch. Return `null` when nothing is cached.
     */
    fun map(
        inbound: MailboxInbound,
        typeBytes: ByteArray = HardwareDetector.typeBytes(),
        appStoreBytes: ByteArray? = HardwareDetector.appStoreBytes(),
        cachedPayload: (String) -> ByteArray? = { null },
        logger: (String) -> Unit = {},
    ): List<MappedPoll> = when (inbound) {
        is MailboxInbound.Snapshot -> mapSnapshot(inbound, typeBytes, appStoreBytes)
        is MailboxInbound.Event -> mapEvent(inbound, typeBytes, appStoreBytes, cachedPayload, logger)
        else -> emptyList()
    }

    private fun mapSnapshot(
        snapshot: MailboxInbound.Snapshot,
        typeBytes: ByteArray,
        appStoreBytes: ByteArray?,
    ): List<MappedPoll> {
        val polls = mutableListOf<MappedPoll>()

        // Snapshot units are keyed "{unit_type}:{unit_id}" → register → payload.
        for (unit in snapshot.units.values) {
            unit[SYSTEM_STATUS_REGISTER]?.let { status ->
                buildSystemPoll(status, typeBytes, appStoreBytes)?.let(polls::add)
            }

            // Reg "03" is a nested zone-id → zone-state DTO map; reg "04" (zone
            // limits) is deliberately skipped — not consumed by MyAir5.
            unit[ZONE_STATE_REGISTER]?.let { zones ->
                for (key in zones.keys()) {
                    val zoneId = key.toIntOrNull() ?: continue
                    val zoneJson = zones.optJSONObject(key) ?: continue
                    polls.add(buildZonePoll(zoneId, zoneJson))
                }
            }
        }

        return polls
    }

    private fun mapEvent(
        event: MailboxInbound.Event,
        typeBytes: ByteArray,
        appStoreBytes: ByteArray?,
        cachedPayload: (String) -> ByteArray?,
        logger: (String) -> Unit,
    ): List<MappedPoll> {
        val payload = event.payload ?: return emptyList()
        return when (event.register) {
            SYSTEM_STATUS_REGISTER -> {
                val cached = cachedPayload(SYSTEM_TAG)
                val poll = if (cached != null) {
                    mergeSystemFields(cached, payload)
                } else {
                    buildSystemPoll(payload, typeBytes, appStoreBytes)
                }
                listOfNotNull(poll)
            }

            ZONE_STATE_REGISTER -> {
                // The zone is part of the CAN address (message-level field), never
                // the payload. Guard <= 0: optInt yields 0 for non-numeric garbage,
                // and MyAir5 zones are 1-based — a zone-0 poll would be bogus.
                val zoneId = event.zone?.takeIf { it > 0 } ?: return emptyList()
                val tag = zoneTag(zoneId)
                val cached = cachedPayload(tag)
                val poll = if (cached != null) {
                    mergeZoneFields(zoneId, cached, payload)
                } else {
                    buildZonePoll(zoneId, payload)
                }
                listOf(poll)
            }

            ZONE_LIMITS_REGISTER -> {
                logger("ignoring zone_limits event for zone ${event.zone} (not consumed by MyAir5)")
                emptyList()
            }

            else -> emptyList()
        }
    }

    // ── getSystemData ─────────────────────────────────────────────

    private fun buildSystemPoll(
        status: JSONObject,
        typeBytes: ByteArray,
        appStoreBytes: ByteArray?,
    ): MappedPoll? {
        val xml = buildSystemXml(status)
        val transformed = GetSystemDataTransformer.transform(xml, typeBytes, appStoreBytes)
            ?: return null
        return MappedPoll(SYSTEM_TAG, transformed)
    }

    /**
     * Builds the pre-transform payload: `type`/`AppStore`/`dhcp`…`gateway`/`MyAppRev`
     * placeholders so [GetSystemDataTransformer] can run, plus the mapped HVAC fields.
     */
    private fun buildSystemXml(status: JSONObject): ByteArray {
        val sb = StringBuilder()
            .append("<request>getSystemData</request>")
            .append("<type>00</type>")
            .append("<AppStore>x</AppStore>")
            .append("<dhcp>0.0.0.0</dhcp><subnet>0.0.0.0</subnet><gateway>0.0.0.0</gateway>")
        for ((tag, value) in systemFieldMutations(status)) {
            sb.append("<$tag>$value</$tag>")
        }
        sb.append("<MyAppRev>0</MyAppRev>")
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Merges sparse register-`"05"` (system status) event fields onto the last cached
     * (already-transformed) `getSystemData` payload. The transformer only ever touches
     * `type`/`AppStore`/the `dhcp`…`gateway` range/`MyAppRev`, so the mapped HVAC tags
     * below survive intact in cache and can be patched directly — no need to re-run the
     * transformer here.
     */
    private fun mergeSystemFields(cached: ByteArray, status: JSONObject): MappedPoll {
        var current = cached
        for ((tag, value) in systemFieldMutations(status)) {
            current = replaceTagOrKeep(current, tag, value)
        }
        return MappedPoll(SYSTEM_TAG, current)
    }

    private fun systemFieldMutations(status: JSONObject): List<Pair<String, String>> {
        val mutations = mutableListOf<Pair<String, String>>()
        status.optStringOrNull("power")?.let { mutations += "state" to it }
        status.optStringOrNull("mode")?.let { mutations += "mode" to it }
        status.optStringOrNull("fan")?.let { mutations += "fan" to it }
        status.optDoubleOrNull("target_temp_c")?.let { mutations += "setTemp" to it.toString() }
        status.optIntOrNull("myzone_id")?.let { mutations += "myZone" to it.toString() }
        status.optStringOrNull("fresh_air")?.let { value ->
            val fa = when (value.lowercase()) {
                "on" -> "2"
                "off" -> "1"
                else -> "0"
            }
            mutations += "FAstatus" to fa
        }
        return mutations
    }

    // ── getZoneData ───────────────────────────────────────────────

    private fun zoneTag(zoneId: Int): String = "getZoneData?zone=$zoneId"

    private fun buildZonePoll(zoneId: Int, zone: JSONObject): MappedPoll {
        val sb = StringBuilder()
            .append("<request>getZoneData</request>")
            .append("<zone>$zoneId</zone>")
        for ((tag, value) in zoneFieldMutations(zone)) {
            sb.append("<$tag>$value</$tag>")
        }
        return MappedPoll(zoneTag(zoneId), sb.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun mergeZoneFields(zoneId: Int, cached: ByteArray, zone: JSONObject): MappedPoll {
        var current = cached
        for ((tag, value) in zoneFieldMutations(zone)) {
            current = replaceTagOrKeep(current, tag, value)
        }
        return MappedPoll(zoneTag(zoneId), current)
    }

    private fun zoneFieldMutations(zone: JSONObject): List<Pair<String, String>> {
        val mutations = mutableListOf<Pair<String, String>>()
        zone.optBooleanOrNull("open")?.let { mutations += "state" to onOff(it) }
        zone.optDoubleOrNull("target_temp_c")?.let { mutations += "temp" to it.toString() }
        zone.optDoubleOrNull("measured_temp_c")?.let { mutations += "measuredTemp" to it.toString() }
        zone.optIntOrNull("damper_pct")?.let { mutations += "damper" to it.toString() }
        zone.optStringOrNull("sensor_type")?.let { mutations += "sensor" to it }
        return mutations
    }

    // ── shared helpers ────────────────────────────────────────────

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    /** Patches an existing tag's content in place; leaves [data] untouched if the tag is absent. */
    private fun replaceTagOrKeep(data: ByteArray, tag: String, value: String): ByteArray = try {
        parser.replaceTagContent(
            data,
            tag.toByteArray(StandardCharsets.UTF_8),
            value.toByteArray(StandardCharsets.UTF_8),
        )
    } catch (e: IllegalArgumentException) {
        data
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return value.ifEmpty { null }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key)
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
}

private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return optBoolean(key)
}
