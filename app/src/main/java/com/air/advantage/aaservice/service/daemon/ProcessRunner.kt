package com.air.advantage.aaservice.service.daemon

/**
 * Runs an argv command and returns its exit code, or `null` if the process
 * could not be started (missing binary, security exception, I/O error) or
 * exceeded the runner timeout.
 *
 * Injected into [SuDaemonLifecycle] so unit tests never need a real `su`.
 */
fun interface ProcessRunner {
    fun run(command: List<String>): Int?
}

/** Default [ProcessRunner] backed by [ProcessBuilder] with a bounded wait. */
object RuntimeProcessRunner : ProcessRunner {
    /** Magisk `control.sh` should finish quickly; hang ⇒ treat as failure. */
    const val DEFAULT_TIMEOUT_MS: Long = 15_000L

    override fun run(command: List<String>): Int? = run(command, DEFAULT_TIMEOUT_MS)

    fun run(command: List<String>, timeoutMs: Long): Int? {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            // Drain stdout off-thread so a full pipe buffer cannot stall waitFor.
            val drain = Thread(
                {
                    runCatching {
                        process.inputStream.bufferedReader().use { it.readText() }
                    }
                },
                "aa-su-stdout-drain",
            ).apply {
                isDaemon = true
                start()
            }
            val exit = waitForExit(process, timeoutMs)
            if (exit == null) {
                // minSdk 19: destroy() only (destroyForcibly is API 26).
                process.destroy()
                drain.join(1_000L)
                return null
            }
            drain.join(1_000L)
            exit
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Wait up to [timeoutMs] for [process] to exit without API-26
     * [Process.waitFor] overload. Polls [Process.exitValue].
     */
    internal fun waitForExit(process: Process, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (true) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) return null
                Thread.sleep(minOf(50L, remaining))
            }
        }
    }
}
