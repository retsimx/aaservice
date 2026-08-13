package com.air.advantage.aaservice.service.daemon

import java.net.URI

/**
 * Returns `true` when [url] points at a loopback daemon host
 * (`127.0.0.1` or `localhost`), so Magisk `cb-daemon` should be started
 * on the tablet. Remote hosts (e.g. Pi) skip Magisk start.
 *
 * Parses the host from a WebSocket (or any) URI; malformed / empty URLs
 * are treated as non-loopback.
 */
fun isLoopbackDaemonUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val host =
        try {
            URI(trimmed).host
        } catch (_: Exception) {
            null
        } ?: return false
    return host.equals("127.0.0.1", ignoreCase = true) ||
        host.equals("localhost", ignoreCase = true)
}
