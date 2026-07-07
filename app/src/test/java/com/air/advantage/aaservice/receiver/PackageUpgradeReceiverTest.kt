package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.air.advantage.aaservice.service.RebootNotificationService
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
class PackageUpgradeReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: PackageUpgradeReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = PackageUpgradeReceiver()
    }

    @Test
    fun receiver_can_be_instantiated() {
        assertNotNull(receiver)
    }

    @Test
    fun `onReceive with matching package starts RebootNotificationService and MainActivity`() {
        val app = context as android.app.Application
        shadowOf(app).clearStartedServices()

        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED).apply {
            data = Uri.parse("package:${context.packageName}")
        }

        receiver.onReceive(context, intent)

        // Verify RebootNotificationService started
        val startedService = shadowOf(app).nextStartedService
        assertNotNull("Service should be started", startedService)
        assertEquals(RebootNotificationService::class.java.name, startedService.component?.className)

        // Verify MainActivity started
        val startedActivity = shadowOf(app).nextStartedActivity
        assertNotNull("MainActivity should be started", startedActivity)
        assertEquals(MainActivity::class.java.name, startedActivity.component?.className)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            startedActivity.flags
        )
    }

    @Test
    fun `onReceive with non matching package returns early`() {
        val app = context as android.app.Application
        shadowOf(app).clearStartedServices()

        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED).apply {
            data = Uri.parse("package:com.other.app")
        }

        receiver.onReceive(context, intent)

        val startedService = shadowOf(app).nextStartedService
        assertNull("Service should not be started", startedService)
    }

    @Test
    fun `onReceive with null data returns early`() {
        val app = context as android.app.Application
        shadowOf(app).clearStartedServices()

        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)

        receiver.onReceive(context, intent)

        val startedService = shadowOf(app).nextStartedService
        assertNull("Service should not be started", startedService)
    }
}