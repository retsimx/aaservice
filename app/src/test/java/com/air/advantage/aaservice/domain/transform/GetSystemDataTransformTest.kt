package com.air.advantage.aaservice.domain.transform

import com.air.advantage.aaservice.data.protocol.FrameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GetSystemDataTransformTest {
    private val realisticPayload =
        (
            "<request>getSystemData</request><type>00</type><AppStore>x</AppStore>" +
                "<dhcp>192.168.1.1</dhcp><subnet>255.255.255.0</subnet><gateway>192.168.1.254</gateway>" +
                "<MyAppRev>14.148</MyAppRev>"
        ).toByteArray(Charsets.UTF_8)

    private val type17 = "17".toByteArray(Charsets.UTF_8)
    private val myAir5 = "MyAir5".toByteArray(Charsets.UTF_8)

    @Test
    fun `transform applies type, AppStore, dhcp to gateway strip and MyAppRev in order`() {
        val result = GetSystemDataTransformer.transform(realisticPayload, type17, myAir5)
        assertNotNull(result)
        assertEquals(
            "<request>getSystemData</request><type>17</type><AppStore>MyAir5</AppStore>" +
                "<MyAppRev>14.150</MyAppRev>",
            String(result!!, Charsets.UTF_8),
        )
    }

    @Test
    fun `transform returns null when MyAppRev tag is absent`() {
        val payload =
            (
                "<request>getSystemData</request><type>00</type><AppStore>x</AppStore>" +
                    "<dhcp>192.168.1.1</dhcp><gateway>192.168.1.254</gateway>"
            ).toByteArray(Charsets.UTF_8)
        assertNull(GetSystemDataTransformer.transform(payload, type17, myAir5))
    }

    @Test
    fun `transform returns null when type tag is absent`() {
        val payload =
            "<request>getSystemData</request><AppStore>x</AppStore><MyAppRev>14.148</MyAppRev>"
                .toByteArray(Charsets.UTF_8)
        assertNull(GetSystemDataTransformer.transform(payload, type17, myAir5))
    }

    @Test
    fun `transform returns null when AppStore tag absent but appStoreBytes provided`() {
        val payload =
            "<request>getSystemData</request><type>00</type><MyAppRev>14.148</MyAppRev>"
                .toByteArray(Charsets.UTF_8)
        assertNull(GetSystemDataTransformer.transform(payload, type17, myAir5))
    }

    @Test
    fun `transform skips AppStore replacement when appStoreBytes is null`() {
        val result = GetSystemDataTransformer.transform(realisticPayload, type17, null)
        assertNotNull(result)
        assertEquals(
            "<request>getSystemData</request><type>17</type><AppStore>x</AppStore>" +
                "<MyAppRev>14.150</MyAppRev>",
            String(result!!, Charsets.UTF_8),
        )
    }

    @Test
    fun `transform leaves payload unchanged when dhcp or gateway tags absent`() {
        val payload =
            (
                "<request>getSystemData</request><type>00</type><AppStore>x</AppStore>" +
                    "<MyAppRev>14.148</MyAppRev>"
            ).toByteArray(Charsets.UTF_8)
        val result = GetSystemDataTransformer.transform(payload, type17, myAir5)
        assertNotNull(result)
        assertEquals(
            "<request>getSystemData</request><type>17</type><AppStore>MyAir5</AppStore>" +
                "<MyAppRev>14.150</MyAppRev>",
            String(result!!, Charsets.UTF_8),
        )
    }

    @Test
    fun `removeRange returns input unchanged when startTag absent`() {
        val parser = FrameParser()
        val data = "<a>1</a><b>2</b>".toByteArray(Charsets.UTF_8)
        val result = parser.removeRange(data, "dhcp", "gateway")
        assertEquals("<a>1</a><b>2</b>", String(result, Charsets.UTF_8))
    }

    @Test
    fun `removeRange returns input unchanged when endTag absent`() {
        val parser = FrameParser()
        val data =
            "<dhcp>192.168.1.1</dhcp><subnet>255.255.255.0</subnet>".toByteArray(Charsets.UTF_8)
        val result = parser.removeRange(data, "dhcp", "gateway")
        assertEquals(
            "<dhcp>192.168.1.1</dhcp><subnet>255.255.255.0</subnet>",
            String(result, Charsets.UTF_8),
        )
    }

    @Test
    fun `removeRange removes from startTag through endTag inclusive spanning intermediate tags`() {
        val parser = FrameParser()
        val data =
            (
                "<a>1</a><dhcp>192.168.1.1</dhcp><subnet>255.255.255.0</subnet>" +
                    "<gateway>192.168.1.254</gateway><b>2</b>"
            ).toByteArray(Charsets.UTF_8)
        val result = parser.removeRange(data, "dhcp", "gateway")
        assertEquals("<a>1</a><b>2</b>", String(result, Charsets.UTF_8))
    }
}
