package com.air.advantage.aaservice.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RebootActionTest {

    @Test
    fun actionRebootDevice_isRebootDevice() {
        assertEquals("com.air.advantage.REBOOT_DEVICE", ServiceHelper.ACTION_REBOOT_DEVICE)
    }
}
