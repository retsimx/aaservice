package com.air.advantage.aaservice.service.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonUrlsTest {

    @Test
    fun `default mailbox URL is loopback`() {
        assertTrue(isLoopbackDaemonUrl("ws://127.0.0.1:2026/v1/mailbox-stream"))
    }

    @Test
    fun `localhost is loopback`() {
        assertTrue(isLoopbackDaemonUrl("ws://localhost:2026/v1/mailbox-stream"))
        assertTrue(isLoopbackDaemonUrl("ws://LocalHost:2026/path"))
    }

    @Test
    fun `remote host is not loopback`() {
        assertFalse(isLoopbackDaemonUrl("ws://192.168.1.10:2026/v1/mailbox-stream"))
        assertFalse(isLoopbackDaemonUrl("ws://pi.local:2026/v1/mailbox-stream"))
        assertFalse(isLoopbackDaemonUrl("wss://example.com/v1/mailbox-stream"))
    }

    @Test
    fun `empty or malformed URLs are not loopback`() {
        assertFalse(isLoopbackDaemonUrl(""))
        assertFalse(isLoopbackDaemonUrl("   "))
        assertFalse(isLoopbackDaemonUrl("not a url"))
        assertFalse(isLoopbackDaemonUrl("ws://"))
    }

    @Test
    fun `IPv6 loopback is not treated as Magisk local without explicit rule`() {
        // Design only names 127.0.0.1 / localhost; ::1 stays remote-skip.
        assertFalse(isLoopbackDaemonUrl("ws://[::1]:2026/v1/mailbox-stream"))
    }
}
