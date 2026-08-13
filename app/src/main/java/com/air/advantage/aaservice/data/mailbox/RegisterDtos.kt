package com.air.advantage.aaservice.data.mailbox

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire-string constants for register payload fields (cb-daemon serde contract).
 */
object MailboxEnum {
    object Mode {
        const val COOL = "cool"
        const val HEAT = "heat"
        const val VENT = "vent"
        const val AUTO = "auto"
        const val DRY = "dry"
        const val MY_AUTO = "myauto"
    }

    object Fan {
        const val OFF = "off"
        const val LOW = "low"
        const val MEDIUM = "medium"
        const val HIGH = "high"
        const val AUTO = "auto"
        const val AUTO_AA = "auto_aa"
    }

    object Power {
        const val ON = "on"
        const val OFF = "off"
    }

    object SensorType {
        const val NO_SENSOR = "no_sensor"
        const val RF = "rf"
        const val WIRED = "wired"
        const val RF2CAN_BOOSTER = "rf2can_booster"
        const val RF_X = "rf_x"
    }

    object ActivationStatus {
        const val NO_CODE = "no_code"
        const val CODE_ENABLED = "code_enabled"
        const val EXPIRED = "expired"
    }

    object UnitType {
        const val DAIKIN = "daikin"
        const val PANASONIC = "panasonic"
        const val FUJITSU = "fujitsu"
        const val SAMSUNG_DVM = "samsung_dvm"
    }

    object Action {
        const val SET_CODE = "set_code"
        const val UNLOCK = "unlock"
    }
}

/** Register 01 — zone configuration. */
data class ZoneConfigDto(
    val header: Int,
    val totalZones: Int,
    val constantZones: Int,
    val constantZoneIds: List<Int>,
    val filterCleanRequired: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("header", header)
        put("total_zones", totalZones)
        put("constant_zones", constantZones)
        put("constant_zone_ids", JSONArray(constantZoneIds))
        put("filter_clean_required", filterCleanRequired)
    }

    companion object {
        fun fromJson(json: JSONObject): ZoneConfigDto = ZoneConfigDto(
            header = json.optInt("header"),
            totalZones = json.optInt("total_zones"),
            constantZones = json.optInt("constant_zones"),
            constantZoneIds = json.optJSONArray("constant_zone_ids")?.let { arr ->
                buildList { for (i in 0 until arr.length()) add(arr.optInt(i)) }
            } ?: emptyList(),
            filterCleanRequired = json.optBoolean("filter_clean_required"),
        )
    }
}

/** Register 02 — unit activation state. */
data class UnitActivationDto(
    val unitType: String,
    val activationStatus: String,
    val dictFwMajor: Int,
    val dictFwMinor: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("unit_type", unitType)
        put("activation_status", activationStatus)
        put("dict_fw_major", dictFwMajor)
        put("dict_fw_minor", dictFwMinor)
    }

    companion object {
        fun fromJson(json: JSONObject): UnitActivationDto = UnitActivationDto(
            unitType = json.optString("unit_type"),
            activationStatus = json.optString("activation_status"),
            dictFwMajor = json.optInt("dict_fw_major"),
            dictFwMinor = json.optInt("dict_fw_minor"),
        )
    }
}

/** Register 03 — zone state. */
data class ZoneStateDto(
    val open: Boolean,
    val damperPct: Int,
    val sensorType: String,
    val targetTempC: Double,
    val measuredTempC: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("open", open)
        put("damper_pct", damperPct)
        put("sensor_type", sensorType)
        put("target_temp_c", targetTempC)
        put("measured_temp_c", measuredTempC)
    }

    companion object {
        fun fromJson(json: JSONObject): ZoneStateDto = ZoneStateDto(
            open = json.optBoolean("open"),
            damperPct = json.optInt("damper_pct"),
            sensorType = json.optString("sensor_type"),
            targetTempC = json.optDouble("target_temp_c"),
            measuredTempC = json.optDouble("measured_temp_c"),
        )
    }
}

/** Register 04 — zone limits. */
data class ZoneLimitsDto(
    val minDamper: Int,
    val maxDamper: Int,
    val motionStatus: Int,
    val motionConfig: Int,
    val zoneError: Int,
    val rssi: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("min_damper", minDamper)
        put("max_damper", maxDamper)
        put("motion_status", motionStatus)
        put("motion_config", motionConfig)
        put("zone_error", zoneError)
        put("rssi", rssi)
    }

    companion object {
        fun fromJson(json: JSONObject): ZoneLimitsDto = ZoneLimitsDto(
            minDamper = json.optInt("min_damper"),
            maxDamper = json.optInt("max_damper"),
            motionStatus = json.optInt("motion_status"),
            motionConfig = json.optInt("motion_config"),
            zoneError = json.optInt("zone_error"),
            rssi = json.optInt("rssi"),
        )
    }
}

/** Register 05 — system status. */
data class SystemStatusDto(
    val power: String,
    val mode: String,
    val fan: String,
    val targetTempC: Double,
    val myzoneId: Int,
    val freshAir: String,
    val rfSysId: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("power", power)
        put("mode", mode)
        put("fan", fan)
        put("target_temp_c", targetTempC)
        put("myzone_id", myzoneId)
        put("fresh_air", freshAir)
        put("rf_sys_id", rfSysId)
    }

    companion object {
        fun fromJson(json: JSONObject): SystemStatusDto = SystemStatusDto(
            power = json.optString("power"),
            mode = json.optString("mode"),
            fan = json.optString("fan"),
            targetTempC = json.optDouble("target_temp_c"),
            myzoneId = json.optInt("myzone_id"),
            freshAir = freshAirFromJson(json),
            rfSysId = json.optInt("rf_sys_id"),
        )

        /** Tolerates both the legacy boolean and the tri-state string wire shape. */
        private fun freshAirFromJson(json: JSONObject): String {
            if (json.isNull("fresh_air") || !json.has("fresh_air")) return "none"
            val value = json.opt("fresh_air")
            return when (value) {
                is Boolean -> if (value) "on" else "off"
                else -> json.optString("fresh_air", "none").lowercase().let {
                    if (it in FRESH_AIR_VALUES) it else "none"
                }
            }
        }

        private val FRESH_AIR_VALUES = setOf("none", "off", "on")
    }
}

/** Register 06 — firmware versions. */
data class FirmwareDto(
    val fwMajor: Int,
    val fwMinor: Int,
    val cbType: Int,
    val rfFwMajor: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fw_major", fwMajor)
        put("fw_minor", fwMinor)
        put("cb_type", cbType)
        put("rf_fw_major", rfFwMajor)
    }

    companion object {
        fun fromJson(json: JSONObject): FirmwareDto = FirmwareDto(
            fwMajor = json.optInt("fw_major"),
            fwMinor = json.optInt("fw_minor"),
            cbType = json.optInt("cb_type"),
            rfFwMajor = json.optInt("rf_fw_major"),
        )
    }
}

/** Register 08 — system error. */
data class SystemErrorDto(
    val errorCode: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("error_code", errorCode)
    }

    companion object {
        fun fromJson(json: JSONObject): SystemErrorDto = SystemErrorDto(
            errorCode = json.optString("error_code"),
        )
    }
}

/** Register 09 — activation code. */
data class ActivationCodeDto(
    val action: String,
    val unlockCode: String,
    val activationDays: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action)
        put("unlock_code", unlockCode)
        put("activation_days", activationDays)
    }

    companion object {
        fun fromJson(json: JSONObject): ActivationCodeDto = ActivationCodeDto(
            action = json.optString("action"),
            unlockCode = json.optString("unlock_code"),
            activationDays = json.optInt("activation_days"),
        )
    }
}

/** Register 0a — unit announcement. Payload is empty; wire content is ignored. */
data class UnitAnnouncementDto(
    val unused: Unit = Unit,
) {
    fun toJson(): JSONObject = JSONObject()

    companion object {
        fun fromJson(json: JSONObject): UnitAnnouncementDto = UnitAnnouncementDto()
    }
}

/** Register 12 read variant — sensor pairing state. */
data class SensorPairingReadDto(
    val sensorUid: String,
    val pairing: Boolean,
    val sensorRev: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sensor_uid", sensorUid)
        put("pairing", pairing)
        put("sensor_rev", sensorRev)
    }

    companion object {
        fun fromJson(json: JSONObject): SensorPairingReadDto = SensorPairingReadDto(
            sensorUid = json.optString("sensor_uid"),
            pairing = json.optBoolean("pairing"),
            sensorRev = json.optInt("sensor_rev"),
        )
    }
}

/** Register 12 write variant — sensor pairing command. */
data class SensorPairingWriteDto(
    val sensorUid: String,
    val zone: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sensor_uid", sensorUid)
        put("zone", zone)
    }

    companion object {
        fun fromJson(json: JSONObject): SensorPairingWriteDto = SensorPairingWriteDto(
            sensorUid = json.optString("sensor_uid"),
            zone = json.optInt("zone"),
        )
    }
}

/** Register 13 — info byte. */
data class InfoByteDto(
    val infoByte: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("info_byte", infoByte)
    }

    companion object {
        fun fromJson(json: JSONObject): InfoByteDto = InfoByteDto(
            infoByte = json.optInt("info_byte"),
        )
    }
}

/** Register 26 — RF device pairing. */
data class RfDevicePairingDto(
    val pairingControl: Int,
    val rfDeviceType: Int,
    val zoneChannel: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pairing_control", pairingControl)
        put("rf_device_type", rfDeviceType)
        put("zone_channel", zoneChannel)
    }

    companion object {
        fun fromJson(json: JSONObject): RfDevicePairingDto = RfDevicePairingDto(
            pairingControl = json.optInt("pairing_control"),
            rfDeviceType = json.optInt("rf_device_type"),
            zoneChannel = json.optInt("zone_channel"),
        )
    }
}

/** Register 27 — RF device calibration. */
data class RfDeviceCalibrationDto(
    val calibrationControl: Int,
    val channel: Int,
    val upDownPosition: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("calibration_control", calibrationControl)
        put("channel", channel)
        put("up_down_position", upDownPosition)
    }

    companion object {
        fun fromJson(json: JSONObject): RfDeviceCalibrationDto = RfDeviceCalibrationDto(
            calibrationControl = json.optInt("calibration_control"),
            channel = json.optInt("channel"),
            upDownPosition = json.optInt("up_down_position"),
        )
    }
}
