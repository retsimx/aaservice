package com.air.advantage.aaservice.data.mailbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mapper uses [JSONObject]; Robolectric supplies the Android org.json implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MyAir5OutboundMailboxMapperTest {

    @Test
    fun `setAircon power maps to system_status`() {
        val msg = """setAircon?json={"aircons":{"ac1":{"info":{"state":"on"}}}}"""
        val actions = MyAir5OutboundMailboxMapper.mapMessageToCb(msg)
        assertEquals(1, actions.size)
        val update = actions[0] as OutboundMailboxAction.Update
        assertEquals("system_status", update.register)
        assertEquals("on", update.payload.getString("power"))
    }

    @Test
    fun `setAircon mode and fan map to system_status`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"info":{"mode":"cool","fan":"high","setTemp":23.0}}}}"""
        val actions = MyAir5OutboundMailboxMapper.mapMessageToCb(msg)
        val update = actions.single() as OutboundMailboxAction.Update
        assertEquals("cool", update.payload.getString("mode"))
        assertEquals("high", update.payload.getString("fan"))
        assertEquals(23.0, update.payload.getDouble("target_temp_c"), 0.001)
        assertTrue(!update.payload.has("power"))
    }

    @Test
    fun `setAircon zone open maps to zone_state`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"zones":{"z02":{"state":"open"}}}}}"""
        val update = MyAir5OutboundMailboxMapper.mapMessageToCb(msg)
            .single() as OutboundMailboxAction.Update
        assertEquals("zone_state", update.register)
        assertEquals(2, update.payload.getInt("zone_id"))
        assertEquals(true, update.payload.getBoolean("open"))
    }

    @Test
    fun `setAircon zone damper and temp`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"zones":{"z01":{"value":80,"setTemp":22}}}}}"""
        val update = MyAir5OutboundMailboxMapper.mapMessageToCb(msg)
            .single() as OutboundMailboxAction.Update
        assertEquals(1, update.payload.getInt("zone_id"))
        assertEquals(80, update.payload.getInt("damper_pct"))
        assertEquals(22.0, update.payload.getDouble("target_temp_c"), 0.001)
    }

    @Test
    fun `combo info then zones ordered system then zone`() {
        val msg =
            """setAircon?json={"aircons":{"ac1":{"info":{"state":"on","mode":"cool"},"zones":{"z01":{"state":"open"}}}}}"""
        val actions = MyAir5OutboundMailboxMapper.mapMessageToCb(msg)
        assertEquals(2, actions.size)
        val sys = actions[0] as OutboundMailboxAction.Update
        val zone = actions[1] as OutboundMailboxAction.Update
        assertEquals("system_status", sys.register)
        assertEquals("zone_state", zone.register)
    }

    @Test
    fun `reg06 flush token maps to Resync`() {
        assertEquals(
            OutboundMailboxAction.Resync,
            MyAir5OutboundMailboxMapper.mapCanTokens(
                MyAir5OutboundMailboxMapper.REG06_FLUSH_TOKEN,
            ),
        )
    }

    @Test
    fun `normal can poll ids map to Ignore`() {
        assertEquals(
            OutboundMailboxAction.Ignore,
            MyAir5OutboundMailboxMapper.mapCanTokens("5 6 7"),
        )
    }

    @Test
    fun `aircon can tokens map to WriteCan`() {
        val tokens = "0701181f3120052a601000000 0801000000600000000000000"
        assertEquals(
            OutboundMailboxAction.WriteCan(
                listOf("0701181f3120052a601000000", "0801000000600000000000000"),
            ),
            MyAir5OutboundMailboxMapper.mapCanTokens(tokens),
        )
    }

    @Test
    fun `lights can tokens map to Ignore`() {
        assertEquals(
            OutboundMailboxAction.Ignore,
            MyAir5OutboundMailboxMapper.mapCanTokens("0201000000000360000000000 0201000000236000000000000"),
        )
    }

    @Test
    fun `setAllZoneSensorData maps to Direct`() {
        assertEquals(
            listOf(OutboundMailboxAction.Direct("setAllZoneSensorData?")),
            MyAir5OutboundMailboxMapper.mapMessageToCb("setAllZoneSensorData?"),
        )
    }

    @Test
    fun `unknown message maps to Ignore`() {
        assertEquals(
            listOf(OutboundMailboxAction.Ignore),
            MyAir5OutboundMailboxMapper.mapMessageToCb("setZoneData?zone=1"),
        )
    }

    @Test
    fun `malformed json maps to Ignore without throwing`() {
        assertEquals(
            listOf(OutboundMailboxAction.Ignore),
            MyAir5OutboundMailboxMapper.mapMessageToCb("setAircon?json={not-json"),
        )
    }

    @Test
    fun `getAllData maps to Resync`() {
        assertEquals(OutboundMailboxAction.Resync, MyAir5OutboundMailboxMapper.mapGetAllData())
    }

    @Test
    fun `message without question mark is Ignore`() {
        assertEquals(
            listOf(OutboundMailboxAction.Ignore),
            MyAir5OutboundMailboxMapper.mapMessageToCb("Temperature"),
        )
    }

@Test
fun writeCanFrameSerializesTokensArray() {
    val frame = MailboxOutbound.writeCan("abc", listOf("0701181f30500040232000000"))
    val json = frame.toJsonString()
    println("WRITECAN_JSON: $json")
    // Must be a JSON array, not a stringified List (daemon serde requires Vec<String>).
    assertEquals("[\"0701181f30500040232000000\"]", JSONObject(json).get("tokens").toString())
}

@Test
fun myZoneMapsToSystemStatusMyzoneId() {
    val actions = MyAir5OutboundMailboxMapper.mapMessageToCb(
        "setAircon?json={\"aircons\":{\"ac1\":{\"info\":{\"myZone\":3}}}}",
    )
    val update = actions.filterIsInstance<OutboundMailboxAction.Update>().single()
    assertEquals("system_status", update.register)
    assertEquals(3, update.payload.getInt("myzone_id"))
}

@Test
fun myZoneInactiveMapsToZero() {
    val actions = MyAir5OutboundMailboxMapper.mapMessageToCb(
        "setAircon?json={\"aircons\":{\"ac1\":{\"info\":{\"myZone\":\"Inactive\"}}}}",
    )
    val update = actions.filterIsInstance<OutboundMailboxAction.Update>().single()
    assertEquals(0, update.payload.getInt("myzone_id"))
}

}

