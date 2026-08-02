package com.air.advantage.aaservice.service.daemon

import android.util.Log

/**
 * [DaemonLifecycle] using the Magisk module shell contract:
 *
 * | Op     | Command                                              |
 * |--------|------------------------------------------------------|
 * | start  | `su -c '/data/adb/cb-daemon/control.sh start'`       |
 * | stop   | `su -c '/data/adb/cb-daemon/control.sh stop'`        |
 * | status | `su -c '/data/adb/cb-daemon/control.sh status'`      |
 *
 * Exit `0` = success; non-zero exit, missing `su` / script, or I/O failure =
 * `false`. Magisk module (D10) may be absent — callers treat failure as Error.
 * Failure paths log the exact [suControl] / [AM_RETRY_TRANSPORT_MODE] operator
 * commands (success stays at Log.d).
 */
class SuDaemonLifecycle(
    private val processRunner: ProcessRunner = RuntimeProcessRunner,
) : DaemonLifecycle {

    override fun start(): Boolean = runControl(OP_START)

    override fun stop(): Boolean = runControl(OP_STOP)

    override fun status(): Boolean = runControl(OP_STATUS)

    private fun runControl(op: String): Boolean {
        val operatorCmd = suControl(op)
        val command = listOf(SU, "-c", "$CONTROL_SCRIPT $op")
        val exit = processRunner.run(command)
        if (exit == null) {
            Log.e(
                TAG,
                "DaemonLifecycle.$op: failed to exec (missing su/binary?). " +
                    "Operator: $operatorCmd ; then $AM_RETRY_TRANSPORT_MODE",
            )
            return false
        }
        if (exit != 0) {
            Log.e(
                TAG,
                "DaemonLifecycle.$op: control.sh exited $exit. " +
                    "Operator: $operatorCmd ; status: ${suControl(OP_STATUS)} ; " +
                    "retry: $AM_RETRY_TRANSPORT_MODE",
            )
            return false
        }
        Log.d(TAG, "DaemonLifecycle.$op: ok")
        return true
    }

    companion object {
        private const val TAG = "AAService2/Daemon"
        const val SU = "su"
        const val CONTROL_SCRIPT = "/data/adb/cb-daemon/control.sh"
        const val OP_START = "start"
        const val OP_STOP = "stop"
        const val OP_STATUS = "status"

        /** Exact operator shell for Magisk control.sh (`start`|`stop`|`status`). */
        fun suControl(op: String): String = "$SU -c '$CONTROL_SCRIPT $op'"

        /**
         * Example `am` one-liner to retry WS mode after fixing Magisk.
         */
        const val AM_RETRY_TRANSPORT_MODE =
            "am start-foreground-service " +
                "-n com.air.advantage.aaservice/.service.UartForegroundService " +
                "-a com.air.advantage.TRANSPORT_MODE_CHANGED " +
                "--es transport_mode ws"

        /** Example `am` one-liner to retry USB mode after a failed Magisk stop. */
        const val AM_RETRY_USB_MODE =
            "am start-foreground-service " +
                "-n com.air.advantage.aaservice/.service.UartForegroundService " +
                "-a com.air.advantage.TRANSPORT_MODE_CHANGED " +
                "--es transport_mode usb"
    }
}
