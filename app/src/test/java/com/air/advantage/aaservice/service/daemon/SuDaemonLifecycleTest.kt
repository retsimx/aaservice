package com.air.advantage.aaservice.service.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SuDaemonLifecycleTest {

    private lateinit var recordedCommands: MutableList<List<String>>
    private var nextExit: Int? = 0
    private var throwMissing: Boolean = false
    private lateinit var lifecycle: SuDaemonLifecycle

    @Before
    fun setUp() {
        recordedCommands = mutableListOf()
        nextExit = 0
        throwMissing = false
        val runner = ProcessRunner { command ->
            recordedCommands += command
            if (throwMissing) null else nextExit
        }
        lifecycle = SuDaemonLifecycle(processRunner = runner)
    }

    @Test
    fun `start invokes su control sh start and returns true on exit 0`() {
        assertTrue(lifecycle.start())
        assertEquals(
            listOf(
                listOf("su", "-c", "/data/adb/cb-daemon/control.sh start"),
            ),
            recordedCommands,
        )
    }

    @Test
    fun `stop invokes su control sh stop and returns true on exit 0`() {
        assertTrue(lifecycle.stop())
        assertEquals(
            listOf(
                listOf("su", "-c", "/data/adb/cb-daemon/control.sh stop"),
            ),
            recordedCommands,
        )
    }

    @Test
    fun `status invokes su control sh status and returns true on exit 0`() {
        assertTrue(lifecycle.status())
        assertEquals(
            listOf(
                listOf("su", "-c", "/data/adb/cb-daemon/control.sh status"),
            ),
            recordedCommands,
        )
    }

    @Test
    fun `start returns false on non-zero exit`() {
        nextExit = 1
        assertFalse(lifecycle.start())
        assertEquals(1, recordedCommands.size)
    }

    @Test
    fun `stop returns false on non-zero exit`() {
        nextExit = 127
        assertFalse(lifecycle.stop())
    }

    @Test
    fun `status returns false on non-zero exit`() {
        nextExit = 2
        assertFalse(lifecycle.status())
    }

    @Test
    fun `start returns false when su binary is missing`() {
        throwMissing = true
        assertFalse(lifecycle.start())
        assertEquals(1, recordedCommands.size)
    }

    @Test
    fun `stop returns false when process cannot start`() {
        throwMissing = true
        assertFalse(lifecycle.stop())
    }

    @Test
    fun `status returns false when process cannot start`() {
        throwMissing = true
        assertFalse(lifecycle.status())
    }

    @Test
    fun `operator command helpers match documented Magisk and am contracts`() {
        assertEquals(
            "su -c '/data/adb/cb-daemon/control.sh start'",
            SuDaemonLifecycle.suControl(SuDaemonLifecycle.OP_START),
        )
        assertEquals(
            "su -c '/data/adb/cb-daemon/control.sh stop'",
            SuDaemonLifecycle.suControl(SuDaemonLifecycle.OP_STOP),
        )
        assertEquals(
            "su -c '/data/adb/cb-daemon/control.sh status'",
            SuDaemonLifecycle.suControl(SuDaemonLifecycle.OP_STATUS),
        )
        assertEquals(
            "am start-foreground-service " +
                "-n com.air.advantage.aaservice2/com.air.advantage.aaservice.service.UartForegroundService " +
                "-a com.air.advantage.TRANSPORT_MODE_CHANGED " +
                "--es transport_mode ws",
            SuDaemonLifecycle.AM_RETRY_TRANSPORT_MODE,
        )
        assertEquals(
            "am start-foreground-service " +
                "-n com.air.advantage.aaservice2/com.air.advantage.aaservice.service.UartForegroundService " +
                "-a com.air.advantage.TRANSPORT_MODE_CHANGED " +
                "--es transport_mode usb",
            SuDaemonLifecycle.AM_RETRY_USB_MODE,
        )
    }
}
