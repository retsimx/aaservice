package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.air.advantage.aaservice.service.UartForegroundService
import com.air.advantage.aaservice.ui.main.MainActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DeviceAdminReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: DeviceAdminReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = DeviceAdminReceiver()
    }

    @Test
    fun receiver_can_be_instantiated() {
        assertNotNull(receiver)
    }

    @Test
    fun `onEnabled starts UartForegroundService`() {
        val app = context as android.app.Application
        shadowOf(app).clearStartedServices()

        receiver.onEnabled(context, Intent())

        val startedService = shadowOf(app).nextStartedService
        assertNotNull("Service should be started", startedService)
        assertEquals(UartForegroundService::class.java.name, startedService.component?.className)
    }

    @Test
    fun `onDisabled stops service with CLOSE_DEVICE action and starts MainActivity`() {
        val app = context as android.app.Application
        shadowOf(app).clearStartedServices()

        receiver.onDisabled(context, Intent())

        // Verify stopped service
        val stoppedService = shadowOf(app).nextStoppedService
        assertNotNull("Service should be stopped", stoppedService)
        assertEquals(UartForegroundService::class.java.name, stoppedService.component?.className)
        assertEquals("com.air.advantage.CLOSE_DEVICE", stoppedService.action)

        // Verify MainActivity started
        val startedActivity = shadowOf(app).nextStartedActivity
        assertNotNull("MainActivity should be started", startedActivity)
        assertEquals(MainActivity::class.java.name, startedActivity.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, startedActivity.flags)
    }
}