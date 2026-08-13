package com.air.advantage.aaservice.domain.model

data class ZoneData(
    val zoneNumber: Int,
    val temperature: Int?,
    val setPoint: Int?,
    val mode: String?,
    val openPercent: Int?,
    val raw: ByteArray,
)
