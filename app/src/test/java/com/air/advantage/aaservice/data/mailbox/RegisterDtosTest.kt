package com.air.advantage.aaservice.data.mailbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DTO round-trip tests (fromJson → toJson → fromJson) with exact snake_case
 * wire key assertions and MailboxEnum wire strings (cb-daemon serde contract).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RegisterDtosTest {
    private fun <T> assertRoundTrip(
        json: JSONObject,
        fromJson: (JSONObject) -> T,
        toJson: (T) -> JSONObject,
        expected: T,
    ) {
        assertEquals(expected, fromJson(json))
        assertEquals(expected, fromJson(toJson(fromJson(json))))
    }

    private fun assertJsonKeys(
        json: JSONObject,
        vararg keys: String,
    ) {
        assertEquals(listOf(*keys).sorted(), json.keys().asSequence().toList().sorted())
    }

    // ── register 01 ──────────────────────────────────────────────

    @Test
    fun `ZoneConfigDto round trips including constant_zone_ids`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("header", 0x20)
                    .put("total_zones", 4)
                    .put("constant_zones", 1)
                    .put("constant_zone_ids", org.json.JSONArray(listOf(1, 3)))
                    .put("filter_clean_required", true),
            fromJson = ZoneConfigDto::fromJson,
            toJson = ZoneConfigDto::toJson,
            expected =
                ZoneConfigDto(
                    header = 0x20,
                    totalZones = 4,
                    constantZones = 1,
                    constantZoneIds = listOf(1, 3),
                    filterCleanRequired = true,
                ),
        )
    }

    // ── register 02 ──────────────────────────────────────────────

    @Test
    fun `UnitActivationDto round trips`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("unit_type", "daikin")
                    .put("activation_status", "code_enabled")
                    .put("dict_fw_major", 3)
                    .put("dict_fw_minor", 4),
            fromJson = UnitActivationDto::fromJson,
            toJson = UnitActivationDto::toJson,
            expected =
                UnitActivationDto(
                    unitType = "daikin",
                    activationStatus = "code_enabled",
                    dictFwMajor = 3,
                    dictFwMinor = 4,
                ),
        )
    }

    // ── register 03 ──────────────────────────────────────────────

    @Test
    fun `ZoneStateDto round trips with doubles`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("open", true)
                    .put("damper_pct", 80)
                    .put("sensor_type", "rf")
                    .put("target_temp_c", 22.5)
                    .put("measured_temp_c", 23.1),
            fromJson = ZoneStateDto::fromJson,
            toJson = ZoneStateDto::toJson,
            expected =
                ZoneStateDto(
                    open = true,
                    damperPct = 80,
                    sensorType = "rf",
                    targetTempC = 22.5,
                    measuredTempC = 23.1,
                ),
        )
    }

    // ── register 04 ──────────────────────────────────────────────

    @Test
    fun `ZoneLimitsDto round trips`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("min_damper", 0)
                    .put("max_damper", 100)
                    .put("motion_status", 1)
                    .put("motion_config", 0)
                    .put("zone_error", 0)
                    .put("rssi", -63),
            fromJson = ZoneLimitsDto::fromJson,
            toJson = ZoneLimitsDto::toJson,
            expected =
                ZoneLimitsDto(
                    minDamper = 0,
                    maxDamper = 100,
                    motionStatus = 1,
                    motionConfig = 0,
                    zoneError = 0,
                    rssi = -63,
                ),
        )
    }

    // ── register 05 ──────────────────────────────────────────────

    @Test
    fun `SystemStatusDto round trips with exact snake_case keys`() {
        val json =
            JSONObject()
                .put("power", "on")
                .put("mode", "cool")
                .put("fan", "auto")
                .put("target_temp_c", 22.5)
                .put("myzone_id", 1)
                .put("fresh_air", "off")
                .put("rf_sys_id", 3)
        val expected =
            SystemStatusDto(
                power = "on",
                mode = "cool",
                fan = "auto",
                targetTempC = 22.5,
                myzoneId = 1,
                freshAir = "off",
                rfSysId = 3,
            )
        assertRoundTrip(json, SystemStatusDto::fromJson, SystemStatusDto::toJson, expected)
        assertJsonKeys(
            expected.toJson(),
            "power",
            "mode",
            "fan",
            "target_temp_c",
            "myzone_id",
            "fresh_air",
            "rf_sys_id",
        )
    }

    @Test
    fun `SystemStatusDto tolerates legacy boolean fresh_air`() {
        val json =
            JSONObject()
                .put("power", "on")
                .put("mode", "cool")
                .put("fan", "auto")
                .put("target_temp_c", 22.5)
                .put("myzone_id", 1)
                .put("fresh_air", true)
                .put("rf_sys_id", 3)
        val dto = SystemStatusDto.fromJson(json)
        assertEquals("on", dto.freshAir)
        assertEquals("on", dto.toJson().getString("fresh_air"))
    }

    // ── register 06 ──────────────────────────────────────────────

    @Test
    fun `FirmwareDto round trips`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("fw_major", 14)
                    .put("fw_minor", 150)
                    .put("cb_type", 2)
                    .put("rf_fw_major", 1),
            fromJson = FirmwareDto::fromJson,
            toJson = FirmwareDto::toJson,
            expected =
                FirmwareDto(
                    fwMajor = 14,
                    fwMinor = 150,
                    cbType = 2,
                    rfFwMajor = 1,
                ),
        )
    }

    // ── register 08 ──────────────────────────────────────────────

    @Test
    fun `SystemErrorDto round trips`() {
        assertRoundTrip(
            json = JSONObject().put("error_code", "E3"),
            fromJson = SystemErrorDto::fromJson,
            toJson = SystemErrorDto::toJson,
            expected = SystemErrorDto(errorCode = "E3"),
        )
    }

    // ── register 09 ──────────────────────────────────────────────

    @Test
    fun `ActivationCodeDto round trips`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("action", "set_code")
                    .put("unlock_code", "1234")
                    .put("activation_days", 30),
            fromJson = ActivationCodeDto::fromJson,
            toJson = ActivationCodeDto::toJson,
            expected =
                ActivationCodeDto(
                    action = "set_code",
                    unlockCode = "1234",
                    activationDays = 30,
                ),
        )
    }

    // ── register 0a ──────────────────────────────────────────────

    @Test
    fun `UnitAnnouncementDto round trips as empty JSON object`() {
        val parsed = UnitAnnouncementDto.fromJson(JSONObject().put("ignored", "x"))
        assertEquals(UnitAnnouncementDto(), parsed)
        assertTrue(parsed.toJson().keys().asSequence().toList().isEmpty())
        assertEquals(UnitAnnouncementDto(), UnitAnnouncementDto.fromJson(parsed.toJson()))
    }

    // ── register 12 read/write variants ──────────────────────────

    @Test
    fun `SensorPairingReadDto round trips`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("sensor_uid", "a1b2c3")
                    .put("pairing", true)
                    .put("sensor_rev", 2),
            fromJson = SensorPairingReadDto::fromJson,
            toJson = SensorPairingReadDto::toJson,
            expected =
                SensorPairingReadDto(
                    sensorUid = "a1b2c3",
                    pairing = true,
                    sensorRev = 2,
                ),
        )
    }

    @Test
    fun `SensorPairingWriteDto round trips`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("sensor_uid", "a1b2c3")
                    .put("zone", 4),
            fromJson = SensorPairingWriteDto::fromJson,
            toJson = SensorPairingWriteDto::toJson,
            expected =
                SensorPairingWriteDto(
                    sensorUid = "a1b2c3",
                    zone = 4,
                ),
        )
    }

    // ── register 13 ──────────────────────────────────────────────

    @Test
    fun `InfoByteDto round trips`() {
        assertRoundTrip(
            json = JSONObject().put("info_byte", 0x42),
            fromJson = InfoByteDto::fromJson,
            toJson = InfoByteDto::toJson,
            expected = InfoByteDto(infoByte = 0x42),
        )
    }

    // ── register 26 ──────────────────────────────────────────────

    @Test
    fun `RfDevicePairingDto round trips`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("pairing_control", 1)
                    .put("rf_device_type", 2)
                    .put("zone_channel", 3),
            fromJson = RfDevicePairingDto::fromJson,
            toJson = RfDevicePairingDto::toJson,
            expected =
                RfDevicePairingDto(
                    pairingControl = 1,
                    rfDeviceType = 2,
                    zoneChannel = 3,
                ),
        )
    }

    // ── register 27 ──────────────────────────────────────────────

    @Test
    fun `RfDeviceCalibrationDto round trips`() {
        assertRoundTrip(
            json =
                JSONObject()
                    .put("calibration_control", 1)
                    .put("channel", 5)
                    .put("up_down_position", 0),
            fromJson = RfDeviceCalibrationDto::fromJson,
            toJson = RfDeviceCalibrationDto::toJson,
            expected =
                RfDeviceCalibrationDto(
                    calibrationControl = 1,
                    channel = 5,
                    upDownPosition = 0,
                ),
        )
    }

    // ── MailboxEnum wire strings ─────────────────────────────────

    @Test
    fun `MailboxEnum mode wire strings`() {
        assertEquals("cool", MailboxEnum.Mode.COOL)
        assertEquals("heat", MailboxEnum.Mode.HEAT)
        assertEquals("vent", MailboxEnum.Mode.VENT)
        assertEquals("auto", MailboxEnum.Mode.AUTO)
        assertEquals("dry", MailboxEnum.Mode.DRY)
        assertEquals("myauto", MailboxEnum.Mode.MY_AUTO)
    }

    @Test
    fun `MailboxEnum fan wire strings`() {
        assertEquals("off", MailboxEnum.Fan.OFF)
        assertEquals("low", MailboxEnum.Fan.LOW)
        assertEquals("medium", MailboxEnum.Fan.MEDIUM)
        assertEquals("high", MailboxEnum.Fan.HIGH)
        assertEquals("auto", MailboxEnum.Fan.AUTO)
        assertEquals("auto_aa", MailboxEnum.Fan.AUTO_AA)
    }

    @Test
    fun `MailboxEnum power wire strings`() {
        assertEquals("on", MailboxEnum.Power.ON)
        assertEquals("off", MailboxEnum.Power.OFF)
    }

    @Test
    fun `MailboxEnum sensorType wire strings`() {
        assertEquals("no_sensor", MailboxEnum.SensorType.NO_SENSOR)
        assertEquals("rf", MailboxEnum.SensorType.RF)
        assertEquals("wired", MailboxEnum.SensorType.WIRED)
        assertEquals("rf2can_booster", MailboxEnum.SensorType.RF2CAN_BOOSTER)
        assertEquals("rf_x", MailboxEnum.SensorType.RF_X)
    }

    @Test
    fun `MailboxEnum activationStatus wire strings`() {
        assertEquals("no_code", MailboxEnum.ActivationStatus.NO_CODE)
        assertEquals("code_enabled", MailboxEnum.ActivationStatus.CODE_ENABLED)
        assertEquals("expired", MailboxEnum.ActivationStatus.EXPIRED)
    }

    @Test
    fun `MailboxEnum unitType wire strings`() {
        assertEquals("daikin", MailboxEnum.UnitType.DAIKIN)
        assertEquals("panasonic", MailboxEnum.UnitType.PANASONIC)
        assertEquals("fujitsu", MailboxEnum.UnitType.FUJITSU)
        assertEquals("samsung_dvm", MailboxEnum.UnitType.SAMSUNG_DVM)
    }

    @Test
    fun `MailboxEnum action wire strings`() {
        assertEquals("set_code", MailboxEnum.Action.SET_CODE)
        assertEquals("unlock", MailboxEnum.Action.UNLOCK)
    }
}
