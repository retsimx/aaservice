package com.air.advantage.aaservice.data.mailbox

import org.json.JSONObject

/** Result of mapping a MyAir5 outbound intent to a mailbox WebSocket action. */
sealed class OutboundMailboxAction {
    data class Update(
        val register: String,
        val payload: JSONObject,
    ) : OutboundMailboxAction()

    /** Raw CAN2 token write (sensor pairing, unit flushes, …). */
    data class WriteCan(
        val tokens: List<String>,
    ) : OutboundMailboxAction()

    /** One-shot raw request; the CB reply is delivered as a DirectReply. */
    data class Direct(
        val payload: String,
    ) : OutboundMailboxAction()

    data object Resync : OutboundMailboxAction()

    data object Ignore : OutboundMailboxAction()
}

/**
 * Maps representative MyAir5 outbound intents to mailbox actions (A5).
 *
 * WS mode only consumes these results; USB keeps stock UART/CAN queues.
 * Never throws on malformed input — returns [OutboundMailboxAction.Ignore].
 */
object MyAir5OutboundMailboxMapper {

    const val REGISTER_SYSTEM_STATUS = "system_status"
    const val REGISTER_ZONE_STATE = "zone_state"

    /** Stock reg-06 flush token used as setCAN dump trigger. */
    const val REG06_FLUSH_TOKEN = "0701000000600000000000000"

    private val ZONE_KEY = Regex("^z(\\d{1,2})$", RegexOption.IGNORE_CASE)

    /**
     * Maps a `MESSAGE_TO_CB` string (`command?query`) to zero or more actions.
     *
     * Mirrors the stock USB behavior: `setAircon` is translated (blocked on
     * USB — MyAir5 sends CAN tokens instead), while every other command is a
     * **verbatim relay** to the CB as a direct message (`setSystemData?…`,
     * `setAllZoneSensorData?`, …). The CB parses the ones it knows and
     * ignores the rest. The stock USB block list (Light/Aircon/Activation/
     * MySystem — except `setAircon`) is mirrored so WS relays exactly what
     * USB would put on the bus.
     */
    fun mapMessageToCb(message: String): List<OutboundMailboxAction> {
        val q = message.indexOf('?')
        if (q < 0) return listOf(OutboundMailboxAction.Ignore)
        val command = message.substring(0, q)
        return when {
            command.equals("setAircon", ignoreCase = true) ->
                mapSetAircon(message.substring(q + 1))
            command.contains("Light") || command.contains("Aircon") ||
                command.contains("Activation") || command.contains("MySystem") ->
                listOf(OutboundMailboxAction.Ignore)
            else -> listOf(OutboundMailboxAction.Direct(message))
        }
    }

    /**
     * Maps a space-separated CAN token string. Reg-06 flush → [Resync]; aircon
     * tokens (unit 07/08: sensor pairing, unit flushes) → [WriteCan]; lights
     * (02) and anything else → [Ignore].
     */
    fun mapCanTokens(canIds: String): OutboundMailboxAction {
        val tokens = canIds.replace("  ", " ").split(" ").filter { it.isNotEmpty() }
        if (tokens.any { it.equals(REG06_FLUSH_TOKEN, ignoreCase = true) }) {
            return OutboundMailboxAction.Resync
        }
        val aircon = tokens.filter { token ->
            val t = token.lowercase()
            t.length == 25 && t.all { it in "0123456789abcdef" } &&
                (t.startsWith("07") || t.startsWith("08"))
        }
        return if (aircon.isEmpty()) {
            OutboundMailboxAction.Ignore
        } else {
            OutboundMailboxAction.WriteCan(aircon)
        }
    }

    /** Explicit full-refresh path (e.g. GET_ALL_DATA in WS mode). */
    fun mapGetAllData(): OutboundMailboxAction = OutboundMailboxAction.Resync

    private fun mapSetAircon(query: String): List<OutboundMailboxAction> {
        val jsonParam = extractJsonParam(query) ?: return listOf(OutboundMailboxAction.Ignore)
        val root = try {
            JSONObject(jsonParam)
        } catch (_: Exception) {
            return listOf(OutboundMailboxAction.Ignore)
        }
        val aircons = root.optJSONObject("aircons") ?: return listOf(OutboundMailboxAction.Ignore)

        val systemActions = mutableListOf<OutboundMailboxAction>()
        val zoneActions = mutableListOf<OutboundMailboxAction>()

        val acKeys = aircons.keys()
        while (acKeys.hasNext()) {
            val acKey = acKeys.next()
            val ac = aircons.optJSONObject(acKey) ?: continue

            ac.optJSONObject("info")?.let { info ->
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
                if (payload.length() > 0) {
                    systemActions += OutboundMailboxAction.Update(
                        register = REGISTER_SYSTEM_STATUS,
                        payload = payload,
                    )
                }
            }

            val zones = ac.optJSONObject("zones") ?: continue
            val zoneKeys = zones.keys()
            while (zoneKeys.hasNext()) {
                val zoneKey = zoneKeys.next()
                val zoneId = parseZoneId(zoneKey) ?: continue
                val zone = zones.optJSONObject(zoneKey) ?: continue
                val payload = JSONObject().put("zone_id", zoneId)
                var hasField = false
                if (zone.has("state") && !zone.isNull("state")) {
                    when (zone.optString("state").lowercase()) {
                        "open" -> {
                            payload.put("open", true)
                            hasField = true
                        }
                        "close", "closed" -> {
                            payload.put("open", false)
                            hasField = true
                        }
                    }
                }
                if (zone.has("value") && !zone.isNull("value")) {
                    payload.put("damper_pct", zone.optInt("value"))
                    hasField = true
                }
                if (zone.has("setTemp") && !zone.isNull("setTemp")) {
                    payload.put("target_temp_c", zone.optDouble("setTemp"))
                    hasField = true
                }
                if (hasField) {
                    zoneActions += OutboundMailboxAction.Update(
                        register = REGISTER_ZONE_STATE,
                        payload = payload,
                    )
                }
            }
        }

        val actions = systemActions + zoneActions
        return actions.ifEmpty { listOf(OutboundMailboxAction.Ignore) }
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
