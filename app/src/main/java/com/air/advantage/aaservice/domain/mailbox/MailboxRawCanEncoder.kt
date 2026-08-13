package com.air.advantage.aaservice.domain.mailbox

import com.air.advantage.aaservice.data.mailbox.MailboxInbound
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Rebuilds stock-shaped `getCAN 1 …` payloads from typed mailbox registers so
 * MyAir5's secure `rawCan` path can populate `:2025` aircons (USB parity).
 *
 * cb-daemon stays a thin CB/register layer; this adapter lives in aaservice
 * and mirrors the daemon aa-mailbox codec wire layouts byte-for-byte.
 */
object MailboxRawCanEncoder {

    private const val UNIT_TYPE_AIRCON = 0x07
    private const val DEST_TABLET = 0x03

    private const val REG_ZONE_CONFIG = 0x01
    private const val REG_UNIT_ACTIVATION = 0x02
    private const val REG_ZONE_STATE = 0x03
    private const val REG_ZONE_LIMITS = 0x04
    private const val REG_SYSTEM_STATUS = 0x05
    private const val REG_FIRMWARE = 0x06
    private const val REG_SYSTEM_ERROR = 0x08
    private const val REG_ACTIVATION_CODE = 0x09
    private const val REG_UNIT_ANNOUNCEMENT = 0x0a
    private const val REG_SENSOR_PAIRING = 0x12
    private const val REG_INFO_BYTE = 0x13
    private const val REG_RF_DEVICE_PAIRING = 0x26
    private const val REG_RF_DEVICE_CALIBRATION = 0x27

    private val zoneBearingRegs = setOf(REG_ZONE_STATE, REG_ZONE_LIMITS)
    private val hexChars = "0123456789abcdefABCDEF"

    /**
     * Rebuilds the full register bank as `getCAN 1 <records…>` from a broker
     * snapshot. Output is deterministic — unit keys sorted, register keys
     * sorted, zone maps numeric-sorted — so the `lastRawCan` dedup in
     * UartForegroundService stays stable.
     *
     * @return `getCAN 1 <records…>` or `null` when nothing usable is present.
     */
    fun encodeGetCan(snapshot: MailboxInbound.Snapshot): String? {
        val records = mutableListOf<String>()
        for (unitKey in snapshot.units.keys.sorted()) {
            val unitType = parseRegister(unitKey.substringBefore(':', "")) ?: UNIT_TYPE_AIRCON
            val unitId = parseUnitId(unitKey.substringAfter(':', "")) ?: continue
            val registers = snapshot.units.getValue(unitKey)
            for (registerKey in registers.keys.sorted()) {
                val reg = parseRegister(registerKey) ?: continue
                val payload = registers.getValue(registerKey)
                if (reg in zoneBearingRegs) {
                    val zoneKeys = payload.keys().asSequence()
                        .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                    for (zoneKey in zoneKeys) {
                        val zone = zoneKey.toIntOrNull() ?: continue
                        val zonePayload = payload.optJSONObject(zoneKey) ?: continue
                        encodeData(reg, zonePayload, zone)?.let { data ->
                            records += recordHex(unitType, unitId, reg, data)
                        }
                    }
                } else {
                    encodeData(reg, payload, 0)?.let { data ->
                        records += recordHex(unitType, unitId, reg, data)
                    }
                }
            }
        }
        if (records.isEmpty()) return null
        return buildString {
            append("getCAN 1")
            for (record in records) {
                append(' ')
                append(record)
            }
        }
    }

    /**
     * Rebuilds a single record as `getCAN 1 <record>` from a broker event.
     * Known registers encode from the typed payload; events whose payload is
     * a raw 14-char lowercase hex string pass those bytes through verbatim.
     *
     * @return `getCAN 1 <record>` or `null` when the event cannot form a record.
     */
    fun encodeEventToCan(event: MailboxInbound.Event): String? {
        val unitType = parseRegister(event.unitType) ?: UNIT_TYPE_AIRCON
        val unitId = parseUnitId(event.unitId) ?: return null
        val reg = parseRegister(event.register) ?: return null
        val payload = event.payload
        val data = if (payload != null) {
            encodeData(reg, payload, event.zone ?: 0)
        } else {
            rawHexData(event.raw.optString("payload", ""))
        } ?: return null
        return "getCAN 1 " + recordHex(unitType, unitId, reg, data)
    }

    private fun encodeData(reg: Int, payload: JSONObject, zone: Int): ByteArray? = when (reg) {
        REG_ZONE_CONFIG -> zoneConfigData(payload)
        REG_UNIT_ACTIVATION -> unitActivationData(payload)
        REG_ZONE_STATE -> zoneStateData(zone, payload)
        REG_ZONE_LIMITS -> zoneLimitsData(zone, payload)
        REG_SYSTEM_STATUS -> systemStatusData(payload)
        REG_FIRMWARE -> firmwareData(payload)
        REG_SYSTEM_ERROR -> systemErrorData(payload)
        REG_ACTIVATION_CODE -> activationCodeData(payload)
        REG_UNIT_ANNOUNCEMENT -> unitAnnouncementData()
        REG_SENSOR_PAIRING -> sensorPairingData(payload)
        REG_INFO_BYTE -> infoByteData(payload)
        REG_RF_DEVICE_PAIRING -> rfDevicePairingData(payload)
        REG_RF_DEVICE_CALIBRATION -> rfDeviceCalibrationData(payload)
        else -> null
    }

    /** Reg 01: `[header][total_zones][constant_zones][const_1][const_2][const_3][filter_clean]`. */
    private fun zoneConfigData(cfg: JSONObject): ByteArray {
        val constantIds = cfg.optJSONArray("constant_zone_ids")
        fun constantAt(index: Int) =
            constantIds?.optInt(index, 0)?.coerceIn(0, 255) ?: 0
        return byteArrayOf(
            cfg.optInt("header", 0).coerceIn(0, 255).toByte(),
            cfg.optInt("total_zones", 0).coerceIn(0, 255).toByte(),
            cfg.optInt("constant_zones", 0).coerceIn(0, 255).toByte(),
            constantAt(0).toByte(),
            constantAt(1).toByte(),
            constantAt(2).toByte(),
            (if (cfg.optBoolean("filter_clean_required", false)) 0x01 else 0x00).toByte(),
        )
    }

    /** Reg 02: `[brand][activation][dict_fw_major][dict_fw_minor][00][00][00]`. */
    private fun unitActivationData(dto: JSONObject): ByteArray {
        val brand = when (dto.optString("unit_type")) {
            "daikin" -> 0x11
            "panasonic" -> 0x12
            "fujitsu" -> 0x13
            "samsung_dvm" -> 0x19
            else -> 0x11
        }
        val activation = when (dto.optString("activation_status")) {
            "no_code" -> 0x00
            "code_enabled" -> 0x01
            "expired" -> 0x02
            else -> 0x00
        }
        return byteArrayOf(
            brand.toByte(),
            activation.toByte(),
            dto.optInt("dict_fw_major", 0).coerceIn(0, 255).toByte(),
            dto.optInt("dict_fw_minor", 0).coerceIn(0, 255).toByte(),
            0x00,
            0x00,
            0x00,
        )
    }

    /** Reg 03: `[zone][(open?0x80:0)|pct][sensor][set_temp_x2][meas_int][meas_dec][00]`. */
    private fun zoneStateData(zone: Int, zoneDto: JSONObject): ByteArray {
        val open = zoneDto.optBoolean("open", false)
        val pct = zoneDto.optInt("damper_pct", 0).coerceIn(0, 100)
        val openPct = (if (open) 0x80 else 0x00) or (pct and 0x7f)
        val sensor = when (zoneDto.optString("sensor_type")) {
            "no_sensor" -> 0x00
            "rf" -> 0x01
            "rf2can_booster" -> 0x03
            "rf_x" -> 0x04
            else -> 0x02
        }
        val setX2 = tempCToX2(zoneDto.optDouble("target_temp_c", 22.0))
        val measured = zoneDto.optDouble("measured_temp_c", 0.0)
        val measInt = measured.toInt().coerceIn(0, 255)
        val measDec = ((measured - measInt) * 10.0).roundToInt().coerceIn(0, 9)
        return byteArrayOf(
            zone.coerceIn(0, 255).toByte(),
            openPct.toByte(),
            sensor.toByte(),
            setX2.toByte(),
            measInt.toByte(),
            measDec.toByte(),
            0x00,
        )
    }

    /** Reg 04: `[zone][min_damper][max_damper][motion_status][motion_config][zone_error][rssi]`. */
    private fun zoneLimitsData(zone: Int, dto: JSONObject): ByteArray = byteArrayOf(
        zone.coerceIn(0, 255).toByte(),
        dto.optInt("min_damper", 0).coerceIn(0, 255).toByte(),
        dto.optInt("max_damper", 0).coerceIn(0, 255).toByte(),
        dto.optInt("motion_status", 0).coerceIn(0, 255).toByte(),
        dto.optInt("motion_config", 0).coerceIn(0, 255).toByte(),
        dto.optInt("zone_error", 0).coerceIn(0, 255).toByte(),
        dto.optInt("rssi", 0).coerceIn(0, 255).toByte(),
    )

    /** Reg 05: `[power][mode][fan][set_temp_x2][myzone_id][fresh_air][rf_sys_id]`. */
    private fun systemStatusData(status: JSONObject): ByteArray {
        val power = if (status.optString("power") == "on") 0x01 else 0x00
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
        val fresh = if (status.optBoolean("fresh_air", false)) 0x02 else 0x01
        val rfSysId = status.optInt("rf_sys_id", 0).coerceIn(0, 255)
        return byteArrayOf(
            power.toByte(),
            mode.toByte(),
            fan.toByte(),
            setTempX2.toByte(),
            myzone.toByte(),
            fresh.toByte(),
            rfSysId.toByte(),
        )
    }

    /** Reg 06: `[fw_major][fw_minor][cb_type][rf_fw_major][00][00][00]`. */
    private fun firmwareData(dto: JSONObject): ByteArray = byteArrayOf(
        dto.optInt("fw_major", 0).coerceIn(0, 255).toByte(),
        dto.optInt("fw_minor", 0).coerceIn(0, 255).toByte(),
        dto.optInt("cb_type", 0).coerceIn(0, 255).toByte(),
        dto.optInt("rf_fw_major", 0).coerceIn(0, 255).toByte(),
        0x00,
        0x00,
        0x00,
    )

    /** Reg 08: `[5 ASCII chars][00][00]` — error code truncated/NUL-padded to 5. */
    private fun systemErrorData(dto: JSONObject): ByteArray {
        val code = dto.optString("error_code").take(5).padEnd(5, '\u0000')
        val bytes = code.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(
            bytes[0],
            bytes[1],
            bytes[2],
            bytes[3],
            bytes[4],
            0x00,
            0x00,
        )
    }

    /**
     * Reg 09: `[action][code_hi][code_lo][days][00][00][00]`.
     * @return null when the action or unlock code is invalid.
     */
    private fun activationCodeData(dto: JSONObject): ByteArray? {
        val action = when (dto.optString("action")) {
            "set_code" -> 0x01
            "unlock" -> 0x02
            else -> return null
        }
        val code = dto.optString("unlock_code").trim()
        if (code.length != 4 || code.any { it !in hexChars }) return null
        val hi = code.substring(0, 2).toIntOrNull(16) ?: return null
        val lo = code.substring(2, 4).toIntOrNull(16) ?: return null
        return byteArrayOf(
            action.toByte(),
            hi.toByte(),
            lo.toByte(),
            dto.optInt("activation_days", 0).coerceIn(0, 255).toByte(),
            0x00,
            0x00,
            0x00,
        )
    }

    /** Reg 0a: `[00]×7`. */
    private fun unitAnnouncementData(): ByteArray = ByteArray(7)

    /**
     * Reg 12 read shape: `[sensor_uid 3B][info][sensor_rev][00][00]`.
     * @return null when the sensor uid is not 6 hex chars.
     */
    private fun sensorPairingData(dto: JSONObject): ByteArray? {
        val uid = dto.optString("sensor_uid").trim()
        if (uid.length != 6 || uid.any { it !in hexChars }) return null
        val b0 = uid.substring(0, 2).toIntOrNull(16) ?: return null
        val b1 = uid.substring(2, 4).toIntOrNull(16) ?: return null
        val b2 = uid.substring(4, 6).toIntOrNull(16) ?: return null
        return byteArrayOf(
            b0.toByte(),
            b1.toByte(),
            b2.toByte(),
            (if (dto.optBoolean("pairing", false)) 0x40 else 0x00).toByte(),
            dto.optInt("sensor_rev", 0).coerceIn(0, 255).toByte(),
            0x00,
            0x00,
        )
    }

    /** Reg 13: `[info_byte][00]×6`. */
    private fun infoByteData(dto: JSONObject): ByteArray = byteArrayOf(
        dto.optInt("info_byte", 0).coerceIn(0, 255).toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
    )

    /** Reg 26: `[pairing_control][rf_device_type][zone_channel][00]×4`. */
    private fun rfDevicePairingData(dto: JSONObject): ByteArray = byteArrayOf(
        dto.optInt("pairing_control", 0).coerceIn(0, 255).toByte(),
        dto.optInt("rf_device_type", 0).coerceIn(0, 255).toByte(),
        dto.optInt("zone_channel", 0).coerceIn(0, 255).toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
    )

    /** Reg 27: `[calibration_control][channel][up_down_position][00]×4`. */
    private fun rfDeviceCalibrationData(dto: JSONObject): ByteArray = byteArrayOf(
        dto.optInt("calibration_control", 0).coerceIn(0, 255).toByte(),
        dto.optInt("channel", 0).coerceIn(0, 255).toByte(),
        dto.optInt("up_down_position", 0).coerceIn(0, 255).toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
    )

    /** @return null unless [raw] is exactly 14 lowercase hex chars. */
    private fun rawHexData(raw: String): ByteArray? {
        if (raw.length != 14 || raw.any { it !in "0123456789abcdef" }) return null
        val bytes = ByteArray(7)
        for (i in 0 until 7) {
            bytes[i] = raw.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
        return bytes
    }

    private fun parseUnitId(raw: String?): Int? {
        if (raw == null) return null
        val hex = raw.trim().lowercase().removePrefix("0x")
        if (hex.isEmpty() || hex.length > 5) return null
        return hex.toIntOrNull(16)
    }

    private fun parseRegister(raw: String?): Int? {
        if (raw == null) return null
        val hex = raw.trim().lowercase().removePrefix("0x")
        if (hex.isEmpty() || hex.length > 2) return null
        return hex.toIntOrNull(16)?.coerceIn(0, 255)
    }

    private fun recordHex(unitType: Int, unitId: Int, reg: Int, data: ByteArray): String {
        require(data.size == 7)
        val out = StringBuilder(25)
        appendHexByte(out, unitType)
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

    private fun tempCToX2(tempC: Double): Int =
        (tempC * 2.0).roundToInt().coerceIn(0, 255)
}
