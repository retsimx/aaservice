package com.air.advantage.aaservice.ui.main

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.receiver.DeviceAdminReceiver
import com.air.advantage.aaservice.service.RebootNotificationService
import com.air.advantage.aaservice.service.UartForegroundService
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
class MainActivityTest {

    @Before
    fun setUp() {
        RebootNotificationService.rebootRequired.set(false)
        MainActivity.isVisible.set(false)
    }

    @After
    fun tearDown() {
        RebootNotificationService.rebootRequired.set(false)
        MainActivity.isVisible.set(false)
    }
    @Test
    fun `onResume with admin active shows correct UI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
        shadowOf(dpm).setActiveAdmin(componentName)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.enable_device_admin).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.disable_device_admin).visibility)
        assertEquals(activity.getString(R.string.setup_correctly), activity.findViewById<TextView>(R.id.status_text).text.toString())
    }

    @Test
    fun `onResume with admin inactive shows correct UI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
        dpm.removeActiveAdmin(componentName)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.enable_device_admin).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.disable_device_admin).visibility)
        assertEquals(activity.getString(R.string.setup_incorrectly), activity.findViewById<TextView>(R.id.status_text).text.toString())
    }

    @Test
    fun `isVisible toggles on onResume and onPause`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        assertFalse(MainActivity.isVisible.get())

        controller.setup()
        assertTrue(MainActivity.isVisible.get())

        controller.pause()
        assertFalse(MainActivity.isVisible.get())
    }

    @Test
    fun `click enable_device_admin starts device admin intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
        dpm.removeActiveAdmin(componentName)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val enableBtn = activity.findViewById<View>(R.id.enable_device_admin)
        enableBtn.performClick()

        val shadowActivity = shadowOf(activity)
        val intent = shadowActivity.nextStartedActivityForResult?.intent
        assertNotNull(intent)
        assertEquals("android.app.action.ADD_DEVICE_ADMIN", intent?.action)
        assertEquals(componentName, intent?.getParcelableExtra("android.app.extra.DEVICE_ADMIN"))
    }

    @Test
    fun `click disable_device_admin triggers dialog`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
        shadowOf(dpm).setActiveAdmin(componentName)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val disableBtn = activity.findViewById<View>(R.id.disable_device_admin)
        disableBtn.performClick()

        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(activity.devicePolicyManager.isAdminActive(componentName))
    }

    @Test
    fun `onResume with reboot required shows reboot layout`() {
        RebootNotificationService.rebootRequired.set(true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertNotNull(activity.findViewById(R.id.version_number))
    }

    @Test
    fun `onResume without reboot required shows main layout`() {
        RebootNotificationService.rebootRequired.set(false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertNotNull(activity.findViewById(R.id.enable_device_admin))
    }

    @Test
    fun `reboot state resets after tearDown`() {
        RebootNotificationService.rebootRequired.set(true)
        assertTrue(RebootNotificationService.rebootRequired.get())

        RebootNotificationService.rebootRequired.set(false)
        assertFalse(RebootNotificationService.rebootRequired.get())
    }

    @Test
    fun `onActivityResult with RESULT_OK starts UART service`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        shadowOf(context as android.app.Application).clearStartedServices()

        activity.onActivityResult(12345, Activity.RESULT_OK, null)

        val serviceIntent = shadowOf(context).nextStartedService
        assertNotNull("Service should be started", serviceIntent)
        assertEquals(UartForegroundService::class.java.name, serviceIntent.component?.className)
    }

    @Test
    fun `onActivityResult with RESULT_CANCELED does not start service`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        shadowOf(context as android.app.Application).clearStartedServices()

        activity.onActivityResult(12345, Activity.RESULT_CANCELED, null)

        val serviceIntent = shadowOf(context).nextStartedService
        assertNull("Service should not be started", serviceIntent)
    }

    @Test
    fun `onActivityResult with wrong requestCode is ignored`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        shadowOf(context as android.app.Application).clearStartedServices()

        activity.onActivityResult(99999, Activity.RESULT_OK, null)

        val serviceIntent = shadowOf(context).nextStartedService
        assertNull("Service should not be started", serviceIntent)
    }

    @Test
    fun `isVisible starts false and toggles correctly`() {
        MainActivity.isVisible.set(false)
        assertFalse(MainActivity.isVisible.get())

        MainActivity.isVisible.set(true)
        assertTrue(MainActivity.isVisible.get())

        MainActivity.isVisible.set(false)
        assertFalse(MainActivity.isVisible.get())
    }

    @Test
    fun `R layout resource IDs are valid`() {
        assertTrue(R.layout.activity_main > 0)
        assertTrue(R.layout.reboot_now > 0)
    }

    @Test
    fun `R id resource IDs are valid`() {
        assertTrue(R.id.enable_device_admin > 0)
        assertTrue(R.id.disable_device_admin > 0)
        assertTrue(R.id.status_text > 0)
        assertTrue(R.id.status_icon > 0)
        assertTrue(R.id.permission_description > 0)
        assertTrue(R.id.show_backup > 0)
    }
}