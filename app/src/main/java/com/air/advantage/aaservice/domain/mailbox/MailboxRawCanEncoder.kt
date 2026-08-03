package com.air.advantage.aaservice.domain.mailbox

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Rebuilds a stock-shaped `getCAN 1 …` payload from a mailbox snapshot so MyAir5's
 * secure `rawCan` path can populate `:2025` aircons (USB parity).
 *
 * cb-daemon stays a thin CB/register layer; this adapter lives in aaservice.
 */
object MailboxRawCanEncoder {

    private const val UNIT_TYPE_AIRCON = 0x07
    private const val DEST_TABLET = 0x03
    private const val REG_ZONE_CONFIG = 0x01
    private const val REG_ZONE_STATE = 0x03
    private const val REG_SYSTEM_STATUS = 0x05

    /**
     * Prefer daemon [can_records] (full bank hex) when present; otherwise rebuild
     * from typed DTOs (`system_status` / `zone_config` / `zones`).
     *
     * @return `getCAN 1 <records…>` or `null` when nothing usable is present.
     */
    fun encodeGetCan(snapshot: JSONObject): String? {
        snapshot.optJSONArray("can_records")?.let { arr ->
            val records = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val rec = arr.optString(i, "").trim().lowercase()
                if (rec.length == 25 && rec.all { it in "0123456789abcdef" }) {
                    records += rec
                }
            }
            if (records.isNotEmpty()) {
                return buildString {
                    append("getCAN 1")
                    for (r in records) {
                        append(' ')
                        append(r)
                    }
                }
            }
        }
        return encodeFromDtos(snapshot)
    }

    private fun encodeFromDtos(snapshot: JSONObject): String? {
        val unitId = parseUnitId(snapshot.optString("unit_id", "")) ?: return null
        val records = mutableListOf<String>()

        snapshot.optJSONObject("zone_config")?.let { cfg ->
            records += recordHex(unitId, REG_ZONE_CONFIG, zoneConfigData(cfg))
        }
        snapshot.optJSONObject("system_status")?.let { status ->
            records += recordHex(unitId, REG_SYSTEM_STATUS, systemStatusData(status))
        }
        snapshot.optJSONObject("zones")?.let { zones ->
            val keys = zones.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            for (key in keys) {
                val zoneId = key.toIntOrNull() ?: continue
                val zone = zones.optJSONObject(key) ?: continue
                records += recordHex(unitId, REG_ZONE_STATE, zoneStateData(zoneId, zone))
            }
        }

        if (records.isEmpty()) return null
        return buildString {
            append("getCAN 1")
            for (r in records) {
                append(' ')
                append(r)
            }
        }
    }

    private fun parseUnitId(raw: String): Int? {
        val hex = raw.trim().lowercase().removePrefix("0x")
        if (hex.isEmpty() || hex.length > 5) return null
        return hex.toIntOrNull(16)
    }

    private fun recordHex(unitId: Int, reg: Int, data: ByteArray): String {
        require(data.size == 7)
        val out = StringBuilder(25)
        appendHexByte(out, UNIT_TYPE_AIRCON)
        appendHexByte(out, DEST_TABLET)
        // 20-bit unit id → 5 hex chars
        out.append("%05x".format(unitId and 0xFFFFF))
        appendHexByte(out, reg)
        for (b in data) appendHexByte(out, b.toInt() and 0xff)
        return out.toString()
    }

    private fun appendHexByte(out: StringBuilder, value: Int) {
        out.append("%02x".format(value and 0xff))
    }

    private fun systemStatusData(status: JSONObject): ByteArray {
        val power = when (status.optString("power")) {
            "on" -> 0x01
            "off" -> 0x00
            else -> 0x01
        }
        val mode = when (status.optString("mode")) {
            "cool" -> 0x01
            "heat" -> 0x02
            "vent" -> 0x03
            "auto" -> 0x04
            "dry" -> 0x05
            "my_auto", "myauto" -> 0x06
            else -> 0x01
        }
        val fan = when (status.optString("fan")) {
            "off" -> 0x00
            "low" -> 0x01
            "medium" -> 0x02
            "high" -> 0x03
            "auto" -> 0x04
            "auto_aa" -> 0x05
            else -> 0x04
        }
        val setTempX2 = tempCToX2(status.optDouble("target_temp_c", 24.0))
        val myzone = status.optInt("myzone_id", 1).coerceIn(0, 255)
        val fresh = if (status.optBoolean("fresh_air", false)) 0x02 else 0x00
        return byteArrayOf(
            power.toByte(),
            mode.toByte(),
            fan.toByte(),
            setTempX2.toByte(),
            myzone.toByte(),
            fresh.toByte(),
            0x00,
        )
    }

    private fun zoneConfigData(cfg: JSONObject): ByteArray {
        val total = cfg.optInt("total_zones", 0).coerceIn(0, 255)
        val constant = cfg.optInt("constant_zones", 0).coerceIn(0, 255)
        val filter = if (cfg.optBoolean("filter_clean_required", false)) 0x01 else 0x00
        return byteArrayOf(
            0x20,
            total.toByte(),
            constant.toByte(),
            0x00,
            0x00,
            0x00,
            filter.toByte(),
        )
    }

    private fun zoneStateData(zoneId: Int, zone: JSONObject): ByteArray {
        val open = zone.optBoolean("open", false)
        val pct = zone.optInt("damper_pct", 0).coerceIn(0, 100)
        val openPct = ((if (open) 0x80 else 0) or (pct and 0x7f))
        val sensor = when (zone.optString("sensor_type")) {
            "no_sensor" -> 0x00
            "rf" -> 0x01
            "wired", "temp" -> 0x02
            "rf2can_booster" -> 0x03
            "rf_x" -> 0x04
            else -> 0x02
        }
        val setX2 = tempCToX2(zone.optDouble("target_temp_c", 22.0))
        val measured = zone.optDouble("measured_temp_c", 0.0)
        val measInt = measured.toInt().coerceIn(0, 255)
        val measDec = ((measured - measInt) * 10.0).roundToInt().coerceIn(0, 9)
        return byteArrayOf(
            zoneId.coerceIn(1, 10).toByte(),
            openPct.toByte(),
            sensor.toByte(),
            setX2.toByte(),
            measInt.toByte(),
            measDec.toByte(),
            0x00,
        )
    }

    private fun tempCToX2(tempC: Double): Int =
        (tempC * 2.0).roundToInt().coerceIn(0, 255)
}
