package com.air.advantage.aaservice.data.mailbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mapper uses [org.json.JSONObject]; Robolectric supplies the Android org.json implementation.
 *
 * Covers the broker surface (B-4): typed writes for setAircon / legacy commands,
 * raw CAN writes with addressing, resync commands, and drop rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MyAir5OutboundMailboxMapperTest {

    private fun asWrite(action: OutboundMailboxAction): OutboundMailboxAction.Write =
        action as OutboundMailboxAction.Write

    private fun typed(write: OutboundMailboxAction.Write): org.json.JSONObject =
        (write.payload as MailboxPayload.Typed).payload

    private fun rawHex(write: OutboundMailboxAction.Write): String =
        (write.payload as MailboxPayload.RawHex).hex

    private fun assertIgnore(actions: List<OutboundMailboxAction>) {
        assertEquals(1, actions.size)
        assertTrue(actions[0] is OutboundMailboxAction.Ignore)
    }

    // ── setAircon info ──────────────────────────────────────────────

    @Test
    fun `setAircon power only maps to system_status write`() {
        val msg = """setAircon?json={"aircons":{"ac1":{"info":{"state":"on"}}}}"""
        val write = asWrite(MyAir5OutboundMailboxMapper.mapMessage(msg).single())
        assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, write.register)
        val payload = typed(write)
        assertEquals("on", payload.getString("power"))
        // sparse: absent fields are not emitted
        assertFalse(payload.has("mode"))
        assertFalse(payload.has("fan"))
        assertFalse(payload.has("target_temp_c"))
        assertNull(write.zone)
        assertNull(write.unitType)
        assertNull(write.unitId)
    }

    @Test
    fun `setAircon mode fan setTemp map to system_status write`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"info":{"mode":"cool","fan":"high","setTemp":23.0}}}}"""
        val write = asWrite(MyAir5OutboundMailboxMapper.mapMessage(msg).single())
        assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, write.register)
        val payload = typed(write)
        assertEquals("cool", payload.getString("mode"))
        assertEquals("high", payload.getString("fan"))
        assertEquals(23.0, payload.getDouble("target_temp_c"), 0.001)
        assertFalse(payload.has("power"))
        assertFalse(payload.has("myzone_id"))
        assertFalse(payload.has("fresh_air"))
        assertNull(write.zone)
    }

    @Test
    fun `setAircon myZone maps to myzone_id`() {
        val msg = """setAircon?json={"aircons":{"ac1":{"info":{"myZone":2}}}}"""
        val write = asWrite(MyAir5OutboundMailboxMapper.mapMessage(msg).single())
        assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, write.register)
        assertEquals(2, typed(write).getInt("myzone_id"))
        assertFalse(typed(write).has("power"))
    }

    @Test
    fun `setAircon freshAir on maps to fresh_air true`() {
        val msg = """setAircon?json={"aircons":{"ac1":{"info":{"freshAir":"on"}}}}"""
        val write = asWrite(MyAir5OutboundMailboxMapper.mapMessage(msg).single())
        assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, write.register)
        assertTrue(typed(write).getBoolean("fresh_air"))
    }

    @Test
    fun `setAircon freshAir off maps to fresh_air false`() {
        val msg = """setAircon?json={"aircons":{"ac1":{"info":{"freshAir":"off"}}}}"""
        val write = asWrite(MyAir5OutboundMailboxMapper.mapMessage(msg).single())
        assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, write.register)
        assertFalse(typed(write).getBoolean("fresh_air"))
    }

    // ── setAircon zones ─────────────────────────────────────────────

    @Test
    fun `setAircon zone open maps to zone_state write with zone in address`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"zones":{"z02":{"state":"open"}}}}}"""
        val write = asWrite(MyAir5OutboundMailboxMapper.mapMessage(msg).single())
        assertEquals(MyAir5OutboundMailboxMapper.REG_ZONE_STATE, write.register)
        assertEquals(2, write.zone)
        val payload = typed(write)
        assertTrue(payload.getBoolean("open"))
        // zone is an address field, never part of the payload
        assertFalse(payload.has("zone"))
        assertFalse(payload.has("zone_id"))
        assertNull(write.unitType)
        assertNull(write.unitId)
    }

    @Test
    fun `setAircon zone closed maps to open false`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"zones":{"z05":{"state":"close"}}}}}"""
        val write = asWrite(MyAir5OutboundMailboxMapper.mapMessage(msg).single())
        assertEquals(5, write.zone)
        assertFalse(typed(write).getBoolean("open"))
    }

    @Test
    fun `setAircon zone damper and setTemp`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"zones":{"z01":{"value":80,"setTemp":22}}}}}"""
        val write = asWrite(MyAir5OutboundMailboxMapper.mapMessage(msg).single())
        assertEquals(MyAir5OutboundMailboxMapper.REG_ZONE_STATE, write.register)
        assertEquals(1, write.zone)
        val payload = typed(write)
        assertEquals(80, payload.getInt("damper_pct"))
        assertEquals(22.0, payload.getDouble("target_temp_c"), 0.001)
        assertFalse(payload.has("open"))
        assertFalse(payload.has("zone"))
    }

    @Test
    fun `setAircon zone out of range ignored`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"zones":{"z11":{"state":"open"}}}}}"""
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage(msg))
    }

    // ── setAircon combo + malformed ─────────────────────────────────

    @Test
    fun `setAircon combo info then zones ordered system then zone`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"info":{"state":"on","mode":"cool"},"zones":{"z01":{"state":"open"}}}}}"""
        val actions = MyAir5OutboundMailboxMapper.mapMessage(msg)
        assertEquals(2, actions.size)
        val sys = asWrite(actions[0])
        val zone = asWrite(actions[1])
        assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, sys.register)
        assertEquals("on", typed(sys).getString("power"))
        assertNull(sys.zone)
        assertEquals(MyAir5OutboundMailboxMapper.REG_ZONE_STATE, zone.register)
        assertEquals(1, zone.zone)
        assertTrue(typed(zone).getBoolean("open"))
    }

    @Test
    fun `setAircon json without aircons ignored`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("""setAircon?json={"foo":"bar"}"""))
    }

    @Test
    fun `setAircon with empty aircons ignored`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("""setAircon?json={"aircons":{}}"""))
    }

    @Test
    fun `setAircon malformed json maps to Ignore without throwing`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("setAircon?json={not-json"))
    }

    @Test
    fun `setAircon without json param ignored`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("setAircon?foo=bar"))
    }

    // ── legacy setSystemData ────────────────────────────────────────

    @Test
    fun `setSystemData mode maps to mode enum`() {
        val expected = mapOf(
            "1" to "cool", "2" to "heat", "3" to "vent",
            "4" to "auto", "5" to "dry", "6" to "myauto",
        )
        for ((mode, name) in expected) {
            val write = asWrite(
                MyAir5OutboundMailboxMapper.mapMessage("setSystemData?mode=$mode").single(),
            )
            assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, write.register)
            assertEquals(name, typed(write).getString("mode"))
            assertNull(write.zone)
        }
    }

    @Test
    fun `setSystemData invalid mode ignored`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("setSystemData?mode=7"))
    }

    @Test
    fun `setSystemData airconOnOff 1 maps power on`() {
        val write = asWrite(
            MyAir5OutboundMailboxMapper.mapMessage("setSystemData?airconOnOff=1").single(),
        )
        assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, write.register)
        assertEquals("on", typed(write).getString("power"))
    }

    @Test
    fun `setSystemData airconOnOff 0 maps power off`() {
        val write = asWrite(
            MyAir5OutboundMailboxMapper.mapMessage("setSystemData?airconOnOff=0").single(),
        )
        assertEquals(MyAir5OutboundMailboxMapper.REG_SYSTEM_STATUS, write.register)
        assertEquals("off", typed(write).getString("power"))
    }

    @Test
    fun `setSystemData other params ignored`() {
        assertIgnore(
            MyAir5OutboundMailboxMapper.mapMessage("setSystemData?unitControlTempsSetting=4"),
        )
    }

    // ── legacy setZoneData ──────────────────────────────────────────

    @Test
    fun `setZoneData zoneSetting 1 maps open true with zone address`() {
        val write = asWrite(
            MyAir5OutboundMailboxMapper.mapMessage("setZoneData?zone=1&zoneSetting=1").single(),
        )
        assertEquals(MyAir5OutboundMailboxMapper.REG_ZONE_STATE, write.register)
        assertEquals(1, write.zone)
        assertTrue(typed(write).getBoolean("open"))
        assertFalse(typed(write).has("zone"))
    }

    @Test
    fun `setZoneData zoneSetting 0 maps open false`() {
        val write = asWrite(
            MyAir5OutboundMailboxMapper.mapMessage("setZoneData?zone=3&zoneSetting=0").single(),
        )
        assertEquals(3, write.zone)
        assertFalse(typed(write).getBoolean("open"))
    }

    @Test
    fun `setZoneData zone out of range ignored`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("setZoneData?zone=11&zoneSetting=1"))
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("setZoneData?zone=0&zoneSetting=1"))
    }

    @Test
    fun `setZoneData name only ignored`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("setZoneData?zone=1&name=Bedroom"))
    }

    // ── drops ───────────────────────────────────────────────────────

    @Test
    fun `zone timer schedule and sensor data ignored`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("setZoneTimer?x=1"))
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("setScheduleData?schedule=1"))
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("setAllZoneSensorData?"))
    }

    @Test
    fun `stock USB block list still ignores Light Aircon Activation MySystem`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("LightAirconSet?x=1"))
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("ActivationCheck?x=1"))
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("AirconOn?x=1"))
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("MySystemUpdate?x=1"))
    }

    @Test
    fun `message without question mark is Ignore`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("Temperature"))
    }

    @Test
    fun `unknown command is Ignore`() {
        assertIgnore(MyAir5OutboundMailboxMapper.mapMessage("SomeCommand?x=1"))
    }

    // ── CAN tokens ──────────────────────────────────────────────────

    @Test
    fun `reg06 flush token maps to resync command`() {
        val actions = MyAir5OutboundMailboxMapper.mapCanTokens(
            MyAir5OutboundMailboxMapper.REG06_FLUSH_TOKEN,
        )
        val command = actions.single() as OutboundMailboxAction.Command
        assertEquals(MailboxCommandAction.RESYNC, command.action)
    }

    @Test
    fun `mixed batch containing reg06 token maps to resync only`() {
        val actions = MyAir5OutboundMailboxMapper.mapCanTokens(
            "0701181f3120052a601000000 0801000000600000000000000",
        )
        val command = actions.single() as OutboundMailboxAction.Command
        assertEquals(MailboxCommandAction.RESYNC, command.action)

        val withLights = MyAir5OutboundMailboxMapper.mapCanTokens(
            "0201000000000360000000000 0701000000600000000000000",
        )
        val command2 = withLights.single() as OutboundMailboxAction.Command
        assertEquals(MailboxCommandAction.RESYNC, command2.action)
    }

    @Test
    fun `aircon can tokens map to raw writes with addressing`() {
        val actions = MyAir5OutboundMailboxMapper.mapCanTokens(
            "0701181f3120052a601000000 08010000005a0000000000000",
        )
        assertEquals(2, actions.size)

        val first = asWrite(actions[0])
        assertEquals("07", first.unitType)
        assertEquals("181f3", first.unitId)
        // slices from the 25-char token: type[0:2] dest[2:4] uid[4:9] register[9:11] data[11:25]
        assertEquals("12", first.register)
        assertEquals("0052a601000000", rawHex(first))
        assertNull(first.zone)

        val second = asWrite(actions[1])
        assertEquals("08", second.unitType)
        assertEquals("00000", second.unitId)
        assertEquals("05", second.register)
        assertEquals("a0000000000000", rawHex(second))
        assertNull(second.zone)
    }

    @Test
    fun `lights can tokens dropped and double spaces collapsed`() {
        val actions = MyAir5OutboundMailboxMapper.mapCanTokens(
            "0201000000000360000000000  0201000000236000000000000",
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `non can garbage maps to empty list`() {
        val actions = MyAir5OutboundMailboxMapper.mapCanTokens("5 6 7")
        assertTrue(actions.isEmpty())
    }

    // ── parseCanToken ───────────────────────────────────────────────

    @Test
    fun `parseCanToken slices a valid 25 char token`() {
        val token = MyAir5OutboundMailboxMapper.parseCanToken(
            "0701000000600000000000000",
        )!!
        assertEquals("07", token.type)
        assertEquals("01", token.dest)
        assertEquals("00000", token.uid)
        assertEquals("06", token.register)
        assertEquals("00000000000000", token.data)
    }

    @Test
    fun `parseCanToken wrong length returns null`() {
        assertNull(MyAir5OutboundMailboxMapper.parseCanToken("070100000060000000000000"))
        assertNull(MyAir5OutboundMailboxMapper.parseCanToken("5"))
    }

    @Test
    fun `parseCanToken non hex returns null`() {
        assertNull(MyAir5OutboundMailboxMapper.parseCanToken("070100000060000000000000z"))
    }

    // ── mapGetAllData ───────────────────────────────────────────────

    @Test
    fun `mapGetAllData maps to resync command`() {
        val command = MyAir5OutboundMailboxMapper.mapGetAllData() as OutboundMailboxAction.Command
        assertEquals(MailboxCommandAction.RESYNC, command.action)
    }
}
