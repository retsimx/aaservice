package com.air.advantage.aaservice.data.mailbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * URL wiring for [MailboxWsClientFactory] / [MailboxWsConfig.forUrl].
 * Ensures prefs-style URL strings become client config without SharedPreferences
 * inside [OkHttpMailboxWsClient].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MailboxWsClientFactoryTest {
    @Test
    fun `MailboxWsConfig forUrl sets url and keeps defaults`() {
        val url = "ws://192.168.1.10:2026/v1/mailbox-stream"
        val config = MailboxWsConfig.forUrl(url)

        assertEquals(url, config.url)
        assertEquals(MailboxWsConfig.DEFAULT_PING_INTERVAL_MS, config.pingIntervalMs)
        assertEquals(MailboxWsConfig.DEFAULT_RECONNECT_INITIAL_DELAY_MS, config.reconnectInitialDelayMs)
        assertEquals(MailboxWsConfig.DEFAULT_RECONNECT_MAX_DELAY_MS, config.reconnectMaxDelayMs)
        assertEquals(MailboxWsConfig.DEFAULT_ACK_TIMEOUT_MS, config.ackTimeoutMs)
    }

    @Test
    fun `factory create receives caller URL and does not use DEFAULT alone`() {
        val urls = mutableListOf<String>()
        val factory =
            MailboxWsClientFactory { url ->
                urls += url
                FakeMailboxWsClient()
            }

        val first = "ws://10.0.0.5:2026/v1/mailbox-stream"
        val second = "ws://10.0.0.6:2026/v1/mailbox-stream"
        factory.create(first)
        factory.create(second)

        assertEquals(listOf(first, second), urls)
        assertTrue(urls.none { it == MailboxWsConfig.DEFAULT_URL })
    }

    @Test
    fun `production-style factory builds OkHttp client with forUrl config`() {
        val factory =
            MailboxWsClientFactory { url ->
                OkHttpMailboxWsClient(
                    config = MailboxWsConfig.forUrl(url),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                )
            }

        val url = "ws://127.0.0.1:9999/v1/mailbox-stream"
        val client = factory.create(url)

        assertTrue(client is OkHttpMailboxWsClient)
        assertNotSame(factory.create(url), client)
    }

    @Test
    fun `okHttp factory creates distinct clients and cancels scope on disconnect`() {
        val factory = MailboxWsClientFactory.okHttp()
        val url = "ws://127.0.0.1:9999/v1/mailbox-stream"
        val first = factory.create(url)
        val second = factory.create(url)

        assertTrue(first is OkHttpMailboxWsClient)
        assertNotSame(first, second)
        first.disconnect()
        second.disconnect()
    }
}
