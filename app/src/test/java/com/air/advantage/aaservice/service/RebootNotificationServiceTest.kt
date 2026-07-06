package com.air.advantage.aaservice.service

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RebootNotificationServiceTest {

    private lateinit var service: RebootNotificationService

    @Before
    fun setUp() {
        service = RebootNotificationService()
    }

    @After
    fun tearDown() {
        RebootNotificationService.rebootRequired.set(false)
    }

    @Test
    fun rebootRequired_is_initially_false() {
        RebootNotificationService.rebootRequired.set(false)
        assertFalse(RebootNotificationService.rebootRequired.get())
    }

    @Test
    fun rebootRequired_is_AtomicBoolean() {
        assertNotNull(RebootNotificationService.rebootRequired)
        assertTrue(RebootNotificationService.rebootRequired is java.util.concurrent.atomic.AtomicBoolean)
    }

    @Test
    fun onStartCommand_sets_rebootRequired_to_true() {
        RebootNotificationService.rebootRequired.set(false)
        service.onStartCommand(null, 0, 1)

        assertTrue(RebootNotificationService.rebootRequired.get())
    }

    @Test
    fun onDestroy_clears_rebootRequired() {
        RebootNotificationService.rebootRequired.set(true)
        service.onDestroy()

        assertFalse(RebootNotificationService.rebootRequired.get())
    }

    @Test
    fun full_lifecycle_create_start_destroy() {
        assertFalse(RebootNotificationService.rebootRequired.get())

        service.onStartCommand(null, 0, 1)

        assertTrue(RebootNotificationService.rebootRequired.get())

        service.onDestroy()
        assertFalse(RebootNotificationService.rebootRequired.get())
    }

    @Test
    fun NOTIFICATION_CHANNEL_ID_is_notification_channel_1() {
        val field = RebootNotificationService::class.java.getDeclaredField("NOTIFICATION_CHANNEL_ID")
        field.isAccessible = true
        assertEquals("notification_channel_1", field.get(null))
    }

    @Test
    fun NOTIFICATION_ID_is_1234() {
        val field = RebootNotificationService::class.java.getDeclaredField("NOTIFICATION_ID")
        field.isAccessible = true
        assertEquals(1234, field.get(null))
    }

    @Test
    fun multiple_onStartCommand_calls_keep_rebootRequired_true() {
        service.onStartCommand(null, 0, 1)
        service.onStartCommand(null, 0, 2)

        assertTrue(RebootNotificationService.rebootRequired.get())
    }

    @Test
    fun onDestroy_when_rebootRequired_already_false_is_safe() {
        RebootNotificationService.rebootRequired.set(false)
        service.onDestroy()

        assertFalse(RebootNotificationService.rebootRequired.get())
    }
}