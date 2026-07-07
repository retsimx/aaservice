package com.air.advantage.aaservice.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.air.advantage.aaservice.receiver.AlertDialogReceiver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RebootNotificationServiceTest {

    @Before
    fun setUp() {
        RebootNotificationService.rebootRequired.set(false)
    }

    @After
    fun tearDown() {
        RebootNotificationService.rebootRequired.set(false)
    }

    @Test
    fun `service initialization starts foreground with correct notification`() {
        val controller = Robolectric.buildService(RebootNotificationService::class.java).create()
        val service = controller.get()
        val shadowService = shadowOf(service)

        assertEquals(1234, shadowService.lastForegroundNotificationId)
        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
    }

    @Test
    fun `onStartCommand sets rebootRequired to true`() {
        val controller = Robolectric.buildService(RebootNotificationService::class.java).create()
        assertFalse(RebootNotificationService.rebootRequired.get())

        controller.startCommand(0, 0)
        assertTrue(RebootNotificationService.rebootRequired.get())
    }

    @Test
    fun `onDestroy clears rebootRequired`() {
        val controller = Robolectric.buildService(RebootNotificationService::class.java).create()
        controller.startCommand(0, 0)
        assertTrue(RebootNotificationService.rebootRequired.get())

        controller.destroy()
        assertFalse(RebootNotificationService.rebootRequired.get())
    }

    @Test
    fun `multiple startCommand calls keep rebootRequired true`() {
        val controller = Robolectric.buildService(RebootNotificationService::class.java).create()
        controller.startCommand(0, 0)
        controller.startCommand(0, 0)
        assertTrue(RebootNotificationService.rebootRequired.get())
    }
}