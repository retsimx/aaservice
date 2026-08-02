package com.air.advantage.aaservice.domain.transform

import com.air.advantage.aaservice.data.protocol.FrameParser

object GetSystemDataTransformer {

    private const val MY_APP_REV = "14.150"

    fun transform(payload: ByteArray, typeBytes: ByteArray, appStoreBytes: ByteArray?): ByteArray? {
        val parser = FrameParser()
        return try {
            var current = parser.replaceTagContent(payload, "type".toByteArray(Charsets.UTF_8), typeBytes)
            if (appStoreBytes != null) {
                current = parser.replaceTagContent(current, "AppStore".toByteArray(Charsets.UTF_8), appStoreBytes)
            }
            current = parser.removeRange(current, "dhcp", "gateway")
            parser.replaceTagContent(current, "MyAppRev".toByteArray(Charsets.UTF_8), MY_APP_REV.toByteArray(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
