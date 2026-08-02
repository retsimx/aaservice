package com.air.advantage.aaservice.service.daemon

/**
 * Port for Magisk `cb-daemon` lifecycle (start / stop / status).
 *
 * Implementations return `true` on success and `false` on any failure
 * (non-zero exit, missing binary, I/O error). Callers must not assume
 * USB accessory state from a failed result.
 *
 * @see SuDaemonLifecycle for the documented `su` / `control.sh` contract
 */
interface DaemonLifecycle {
    /** Start the Magisk-held daemon. */
    fun start(): Boolean

    /** Stop the daemon (idempotent when already stopped, per control.sh). */
    fun stop(): Boolean

    /** Query whether the daemon appears running / healthy. */
    fun status(): Boolean
}
