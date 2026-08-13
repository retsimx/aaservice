package com.air.advantage.aaservice.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun `onDestroy does not clear rebootRequired`() {
        val controller = Robolectric.buildService(RebootNotificationService::class.java).create()
        controller.startCommand(0, 0)
        assertTrue(RebootNotificationService.rebootRequired.get())

        controller.destroy()
        assertTrue(RebootNotificationService.rebootRequired.get())
    }

    @Test
    fun `onCreate deletes legacy notification channel`() {
        val legacyChannel =
            ApplicationProvider.getApplicationContext<Context>()
                .getString(com.air.advantage.aaservice.R.string.service_name) + " Notification"
        val nm =
            ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(legacyChannel, "legacy", NotificationManager.IMPORTANCE_LOW),
        )

        Robolectric.buildService(RebootNotificationService::class.java).create()

        assertTrue(shadowOf(nm).isChannelDeleted(legacyChannel))
    }

    @Test
    fun `multiple startCommand calls keep rebootRequired true`() {
        val controller = Robolectric.buildService(RebootNotificationService::class.java).create()
        controller.startCommand(0, 0)
        controller.startCommand(0, 0)
        assertTrue(RebootNotificationService.rebootRequired.get())
    }
}
