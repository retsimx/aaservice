package com.air.advantage.aaservice.ui.usb

import androidx.test.core.app.ApplicationProvider
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.service.RebootNotificationService
import com.air.advantage.aaservice.service.UartForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UsbConnectActivityTest {
    @Before
    fun setUp() {
        RebootNotificationService.rebootRequired.set(false)
    }

    @After
    fun tearDown() {
        RebootNotificationService.rebootRequired.set(false)
    }

    @Test
    fun `onCreate starts UART service when reboot not required`() {
        RebootNotificationService.rebootRequired.set(false)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).clearStartedServices()

        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()

        val serviceIntent = shadowOf(context).nextStartedService
        assertNotNull("Service should be started", serviceIntent)
        assertEquals(UartForegroundService::class.java.name, serviceIntent.component?.className)
    }

    @Test
    fun `onCreate finishes when reboot not required`() {
        RebootNotificationService.rebootRequired.set(false)
        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `onCreate does not set content view when reboot not required`() {
        RebootNotificationService.rebootRequired.set(false)
        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = controller.get()
        assertNull(activity.findViewById(R.id.version_number))
    }

    @Test
    fun `onCreate sets reboot content view when reboot required`() {
        RebootNotificationService.rebootRequired.set(true)
        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity.findViewById(R.id.version_number))
    }

    @Test
    fun `onCreate does not start UART service when reboot required`() {
        RebootNotificationService.rebootRequired.set(true)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).clearStartedServices()

        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()

        val serviceIntent = shadowOf(context).nextStartedService
        assertNull("Service should not be started", serviceIntent)
    }

    @Test
    fun `onCreate does not finish when reboot required`() {
        RebootNotificationService.rebootRequired.set(true)
        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `finishIfShowing finishes activity when instance is set`() {
        RebootNotificationService.rebootRequired.set(true)
        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(activity.isFinishing)

        UsbConnectActivity.finishIfShowing()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `finishIfShowing does nothing when no instance`() {
        RebootNotificationService.rebootRequired.set(true)
        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = controller.get()
        controller.destroy()

        val field = UsbConnectActivity::class.java.getDeclaredField("instance")
        field.isAccessible = true
        val instance = field.get(null) as? WeakReference<*>
        assertNull(instance)
    }

    @Test
    fun `onCreate sets instance reference`() {
        RebootNotificationService.rebootRequired.set(true)
        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = controller.get()

        val field = UsbConnectActivity::class.java.getDeclaredField("instance")
        field.isAccessible = true
        val instance = field.get(null) as? WeakReference<*>
        assertNotNull(instance)
        assertEquals(activity, instance?.get())
    }

    @Test
    fun `onDestroy clears instance when it matches`() {
        RebootNotificationService.rebootRequired.set(true)
        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = controller.get()

        controller.destroy()

        val field = UsbConnectActivity::class.java.getDeclaredField("instance")
        field.isAccessible = true
        val instance = field.get(null) as? WeakReference<*>
        assertNull(instance)
    }

    @Test
    fun `onDestroy does not clear instance when different activity`() {
        RebootNotificationService.rebootRequired.set(true)
        val controller = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = controller.get()

        val otherActivity = Robolectric.buildActivity(UsbConnectActivity::class.java).get()
        val field = UsbConnectActivity::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, WeakReference(otherActivity))

        controller.destroy()

        val instanceVal = field.get(null) as? WeakReference<*>
        assertEquals(otherActivity, instanceVal?.get())
    }
}
