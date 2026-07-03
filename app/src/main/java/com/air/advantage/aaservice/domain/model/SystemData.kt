package com.air.advantage.aaservice.domain.model

data class SystemData(
    val type: Int?,
    val appStore: String?,
    val myAppRev: String?,
    val dhcp: String?,
    val gateway: String?,
    val raw: ByteArray
)
