package com.air.advantage.aaservice.util

import android.content.Context

object FujitsuDetector {
    private const val FUJITSU_PACKAGE_MARKER = "fgassist"

    @JvmStatic
    fun isFujitsuVariant(context: Context): Boolean =
        context.packageName.contains(FUJITSU_PACKAGE_MARKER)
}