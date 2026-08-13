package com.air.advantage.aaservice.data.mailbox

import org.json.JSONObject

/** Result of mapping a MyAir5 outbound intent to a broker mailbox action. */
sealed class OutboundMailboxAction {
    /**
     * Write a register value. [zone] is only for zone-bearing registers (03/04);
     * [unitType]/[unitId] address a specific unit — CAN raw writes only.
     */
    data class Write(
        val register: String,          // hex: "05", "03", or raw token reg ("0a", "12", ...)
        val payload: MailboxPayload,   // Typed(JSONObject) | RawHex(hex)
        val zone: Int? = null,         // zone-bearing regs 03/04
        val unitType: String? = null,  // hex "07"/"08" — CAN raw writes only
        val unitId: String? = null,    // 5-hex — CAN raw writes only
    ) : OutboundMailboxAction()

    /** One-shot raw register read; optional addressing fields. */
    data class Read(
        val register: String,
        val zone: Int? = null,
        val unitType: String? = null,
        val unitId: String? = null,
    ) : OutboundMailboxAction()

    /** One-shot broker command (e.g. [MailboxCommandAction.RESYNC]). */
    data class Command(val action: String) : OutboundMailboxAction()

    /** Not representable on the broker surface; [reason] is for logging. */
    data class Ignore(val reason: String) : OutboundMailboxAction()
}

/** A parsed CAN token: 25-char hex, sliced into its wire fields. */
data class CanToken(
    val type: String,
    val dest: String,
    val uid: String,
    val register: String,
    val data: String,
)

/**
 * Maps representative MyAir5 outbound intents to broker mailbox actions (D-1
 * cb-daemon contract). Raw CAN writes and one-shot reads are not wired to the
 * client yet (B-2); dispatch logs them. Never throws on malformed input —
 * returns [OutboundMailboxAction.Ignore] with a reason.
 */
object MyAir5OutboundMailboxMapper {

    /** Register 05 — system status (hex, daemon `RegId::from_hex`). */
    const val REG_SYSTEM_STATUS = "05"

    /** Register 03 — zone state (hex, daemon `RegId::from_hex`). */
    const val REG_ZONE_STATE = "03"

    /** Stock reg-06 flush token; any reg-06 CAN token triggers resync. */
    const val REG06_FLUSH_TOKEN = "0701000000600000000000000"

    /**
     * Registers the daemon treats as internal (`07`) or read-only
     * (`02`, `06`, `08`, `0a`) in its write policy; raw token writes to
     * these are dropped instead of rejected with an ERROR ack.
     */
    val DAEMON_NON_WRITABLE_REGISTERS = setOf("07", "02", "06", "08", "0a")

    private val ZONE_KEY = Regex("^z(\\d{1,2})$", RegexOption.IGNORE_CASE)

    private val MODE_BY_ID = mapOf(
        1 to MailboxEnum.Mode.COOL, 2 to MailboxEnum.Mode.HEAT, 3 to MailboxEnum.Mode.VENT,
        4 to MailboxEnum.Mode.AUTO, 5 to MailboxEnum.Mode.DRY, 6 to MailboxEnum.Mode.MY_AUTO,
    )

    /**
     * Maps a `MESSAGE_TO_CB` string (`command?query`) to zero or more actions.
     *
     * `setAircon?json=` becomes sparse typed writes (system first, then zones);
     * legacy commands (`setSystemData?mode`, `airconOnOff`, `setZoneData?
     * zoneSetting`) become typed writes; everything without a register
     * representation — names, timers, schedules, sensor data, stock block-list
     * commands (Light/Aircon/Activation/MySystem, except `setAircon`) — is
     * ignored with a reason.
     */
    fun mapMessage(message: String): List<OutboundMailboxAction> {
        val q = message.indexOf('?')
        if (q < 0) return listOf(OutboundMailboxAction.Ignore("no query"))
        val command = message.substring(0, q)
        return when {
            command.equals("setAircon", ignoreCase = true) ->
                mapSetAircon(message.substring(q + 1))
            command.contains("Light", ignoreCase = true) ||
                command.contains("Aircon", ignoreCase = true) ||
                command.contains("Activation", ignoreCase = true) ||
                command.contains("MySystem", ignoreCase = true) ->
                listOf(OutboundMailboxAction.Ignore("blocked command"))
            command.equals("setSystemData", ignoreCase = true) ->
                mapSetSystemData(parseQueryParams(message.substring(q + 1)))
            command.equals("setZoneData", ignoreCase = true) ->
                mapSetZoneData(parseQueryParams(message.substring(q + 1)))
            command.equals("setZoneTimer", ignoreCase = true) ||
                command.equals("setScheduleData", ignoreCase = true) ||
                command.equals("setAllZoneSensorData", ignoreCase = true) ->
                listOf(OutboundMailboxAction.Ignore("no register representation"))
            else -> listOf(OutboundMailboxAction.Ignore("unmapped command"))
        }
    }

    /**
     * Maps a space-separated CAN token string to zero or more actions. Any
     * reg-06 token (flush detection) maps the whole batch to
     * [MailboxCommandAction.RESYNC]; aircon tokens (type 07/08) become raw
     * writes addressed to their unit; lights (02), other types and malformed
     * tokens are dropped.
     */
    fun mapCanTokens(canIds: String): List<OutboundMailboxAction> {
        val tokens = canIds.replace("  ", " ").split(" ").filter { it.isNotEmpty() }
        for (token in tokens) {
            val parsed = parseCanToken(token) ?: continue
            if (parsed.register == "06") {
                return listOf(OutboundMailboxAction.Command(MailboxCommandAction.RESYNC))
            }
        }
        return tokens.mapNotNull { token ->
            val parsed = parseCanToken(token) ?: return@mapNotNull null
            when (parsed.type) {
                "02" -> null
                "07", "08" -> {
                    // Registers the daemon treats as internal or read-only
                    // (aa-mailbox write policy): writes are rejected with an
                    // ERROR ack, so drop them here to avoid spurious alerts.
                    if (parsed.register in DAEMON_NON_WRITABLE_REGISTERS) {
                        null
                    } else {
                        OutboundMailboxAction.Write(
                            register = parsed.register,
                            payload = MailboxPayload.RawHex(parsed.data),
                            unitType = parsed.type,
                            unitId = parsed.uid,
                        )
                    }
                }
                else -> null
            }
        }
    }

    /** Explicit full-refresh path (e.g. GET_ALL_DATA in WS mode). */
    fun mapGetAllData(): OutboundMailboxAction =
        OutboundMailboxAction.Command(MailboxCommandAction.RESYNC)

    /**
     * Parses a 25-char lowercase hex CAN token into its wire fields:
     * `type[0:2]`, `dest[2:4]`, `uid[4:9]`, `register[9:11]`, `data[11:25]`.
     * Returns null for wrong length or non-hex content.
     */
    fun parseCanToken(token: String): CanToken? {
        val t = token.lowercase()
        if (t.length != 25) return null
        if (t.any { it !in "0123456789abcdef" }) return null
        return CanToken(
            type = t.substring(0, 2),
            dest = t.substring(2, 4),
            uid = t.substring(4, 9),
            register = t.substring(9, 11),
            data = t.substring(11, 25),
        )
    }

    private fun mapSetAircon(query: String): List<OutboundMailboxAction> {
        val jsonParam = extractJsonParam(query) ?: return listOf(OutboundMailboxAction.Ignore("malformed setAircon json"))
        val root = try {
            JSONObject(jsonParam)
        } catch (_: Exception) {
            return listOf(OutboundMailboxAction.Ignore("malformed setAircon json"))
        }
        val aircons = root.optJSONObject("aircons") ?: return listOf(OutboundMailboxAction.Ignore("malformed setAircon json"))

        val systemActions = mutableListOf<OutboundMailboxAction>()
        val zoneActions = mutableListOf<OutboundMailboxAction>()

        val acKeys = aircons.keys()
        while (acKeys.hasNext()) {
            val ac = aircons.optJSONObject(acKeys.next()) ?: continue
            mapSetAirconInfo(ac)?.let { systemActions += it }
            zoneActions += mapSetAirconZones(ac)
        }

        val actions = systemActions + zoneActions
        return actions.ifEmpty { listOf(OutboundMailboxAction.Ignore("empty setAircon payload")) }
    }

    /**
     * Maps `info` fields to a sparse system-status write (only present fields
     * are emitted), or null when nothing maps.
     */
    private fun mapSetAirconInfo(ac: JSONObject): OutboundMailboxAction? {
        val info = ac.optJSONObject("info") ?: return null
        val payload = JSONObject()
        if (info.has("state") && !info.isNull("state")) {
            payload.put("power", info.optString("state"))
        }
        if (info.has("mode") && !info.isNull("mode")) {
            payload.put("mode", info.optString("mode"))
        }
        if (info.has("fan") && !info.isNull("fan")) {
            payload.put("fan", info.optString("fan"))
        }
        if (info.has("setTemp") && !info.isNull("setTemp")) {
            payload.put("target_temp_c", info.optDouble("setTemp"))
        }
        if (info.has("myZone") && !info.isNull("myZone")) {
            payload.put("myzone_id", info.optInt("myZone"))
        }
        if (info.has("freshAir") && !info.isNull("freshAir")) {
            when (info.optString("freshAir").lowercase()) {
                "on" -> payload.put("fresh_air", "on")
                "off" -> payload.put("fresh_air", "off")
                "none" -> payload.put("fresh_air", "none")
            }
        }
        if (payload.length() == 0) return null
        return OutboundMailboxAction.Write(
            register = REG_SYSTEM_STATUS,
            payload = MailboxPayload.Typed(payload),
        )
    }

    /**
     * Maps `zones` to one zone-state write per mapped zone; the zone id goes in
     * the write address (never the payload).
     */
    private fun mapSetAirconZones(ac: JSONObject): List<OutboundMailboxAction> {
        val zones = ac.optJSONObject("zones") ?: return emptyList()
        val zoneActions = mutableListOf<OutboundMailboxAction>()
        val zoneKeys = zones.keys()
        while (zoneKeys.hasNext()) {
            val zoneKey = zoneKeys.next()
            val zoneId = parseZoneId(zoneKey) ?: continue
            val zone = zones.optJSONObject(zoneKey) ?: continue
            val payload = JSONObject()
            if (zone.has("state") && !zone.isNull("state")) {
                when (zone.optString("state").lowercase()) {
                    "open" -> payload.put("open", true)
                    "close", "closed" -> payload.put("open", false)
                }
            }
            if (zone.has("value") && !zone.isNull("value")) {
                payload.put("damper_pct", zone.optInt("value"))
            }
            if (zone.has("setTemp") && !zone.isNull("setTemp")) {
                payload.put("target_temp_c", zone.optDouble("setTemp"))
            }
            if (payload.length() > 0) {
                zoneActions += OutboundMailboxAction.Write(
                    register = REG_ZONE_STATE,
                    payload = MailboxPayload.Typed(payload),
                    zone = zoneId,
                )
            }
        }
        return zoneActions
    }

    private fun mapSetSystemData(params: Map<String, String>): List<OutboundMailboxAction> {
        val mode = params["mode"]?.toIntOrNull()
        if (mode != null) {
            val enumValue = MODE_BY_ID[mode]
                ?: return listOf(OutboundMailboxAction.Ignore("no register representation"))
            return listOf(
                OutboundMailboxAction.Write(
                    register = REG_SYSTEM_STATUS,
                    payload = MailboxPayload.Typed(JSONObject().put("mode", enumValue)),
                ),
            )
        }
        val onOff = params["airconOnOff"]
        if (onOff != null) {
            val power = when (onOff) {
                "1" -> MailboxEnum.Power.ON
                "0" -> MailboxEnum.Power.OFF
                else -> return listOf(OutboundMailboxAction.Ignore("no register representation"))
            }
            return listOf(
                OutboundMailboxAction.Write(
                    register = REG_SYSTEM_STATUS,
                    payload = MailboxPayload.Typed(JSONObject().put("power", power)),
                ),
            )
        }
        return listOf(OutboundMailboxAction.Ignore("no register representation"))
    }

    private fun mapSetZoneData(params: Map<String, String>): List<OutboundMailboxAction> {
        val zone = params["zone"]?.toIntOrNull() ?: return listOf(OutboundMailboxAction.Ignore("no register representation"))
        if (zone !in 1..10) return listOf(OutboundMailboxAction.Ignore("no register representation"))
        val setting = params["zoneSetting"] ?: return listOf(OutboundMailboxAction.Ignore("no register representation"))
        val open = when (setting) {
            "1" -> true
            "0" -> false
            else -> return listOf(OutboundMailboxAction.Ignore("no register representation"))
        }
        return listOf(
            OutboundMailboxAction.Write(
                register = REG_ZONE_STATE,
                payload = MailboxPayload.Typed(JSONObject().put("open", open)),
                zone = zone,
            ),
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq < 0) null else part.substring(0, eq) to part.substring(eq + 1)
        }.toMap()
    }

    private fun extractJsonParam(query: String): String? {
        // Typical: json={...}  (may be the whole query)
        val prefix = "json="
        val idx = query.indexOf(prefix, ignoreCase = true)
        if (idx < 0) return null
        return query.substring(idx + prefix.length).ifEmpty { null }
    }

    private fun parseZoneId(key: String): Int? {
        val match = ZONE_KEY.matchEntire(key.trim()) ?: return null
        val id = match.groupValues[1].toIntOrNull() ?: return null
        return id.takeIf { it in 1..10 }
    }
}
