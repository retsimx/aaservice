package com.air.advantage.aaservice.ui.main

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.view.View
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.application.AAServiceApp
import com.air.advantage.aaservice.receiver.DeviceAdminReceiver
import com.air.advantage.aaservice.service.RebootNotificationService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = AAServiceApp::class, manifest = Config.NONE)
class MainActivityTest {

    private lateinit var activity: MainActivity
    private lateinit var devicePolicyManager: DevicePolicyManager

    private val componentName = ComponentName("com.air.advantage.aaservice", DeviceAdminReceiver::class.java.name)

    @Before
    fun setUp() {
        devicePolicyManager = mock(DevicePolicyManager::class.java)
        activity = spy(Robolectric.buildActivity(MainActivity::class.java).create().get())
        activity.devicePolicyManager = devicePolicyManager
    }

    @After
    fun tearDown() {
        RebootNotificationService.rebootRequired.set(false)
        MainActivity.isVisible.set(false)
    }

    // ── Instantiation ────────────────────────────────────────────

    @Test
    fun activity_can_be_instantiated() {
        assertNotNull(MainActivity())
    }

    @Test
    fun activity_is_an_Activity() {
        val a: Any = MainActivity()
        assertTrue(a is Activity)
    }

    @Test
    fun isVisible_is_AtomicBoolean() {
        assertNotNull(MainActivity.isVisible)
        assertTrue(MainActivity.isVisible is java.util.concurrent.atomic.AtomicBoolean)
    }

    // ── Device admin state ───────────────────────────────────────

    @Test
    fun `onResume with admin active shows correct UI`() {
        whenever(devicePolicyManager.isAdminActive(componentName)).thenReturn(true)

        activity.onResume()

        verify(devicePolicyManager).isAdminActive(componentName)
    }

    @Test
    fun `onResume with admin inactive shows correct UI`() {
        whenever(devicePolicyManager.isAdminActive(componentName)).thenReturn(false)

        activity.onResume()

        verify(devicePolicyManager).isAdminActive(componentName)
    }

    @Test
    fun `isVisible toggles on onResume and onPause`() {
        assertFalse(MainActivity.isVisible.get())

        activity.onResume()
        assertTrue(MainActivity.isVisible.get())

        activity.onPause()
        assertFalse(MainActivity.isVisible.get())
    }

    // ── Enable button click ──────────────────────────────────────

    @Test
    fun `click enable_device_admin starts device admin intent`() {
        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.enable_device_admin)

        activity.onClick(view)

        verify(activity).startActivityForResult(any(Intent::class.java), eq(12345))
    }

    @Test
    fun `click enable_device_admin passes correct action`() {
        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.enable_device_admin)

        activity.onClick(view)

        verify(activity).startActivityForResult(
            argThat<Intent> { action == "android.app.action.ADD_DEVICE_ADMIN" },
            eq(12345)
        )
    }

    // ── Disable button click ─────────────────────────────────────

    @Test
    fun `click disable_device_admin triggers dialog`() {
        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.disable_device_admin)

        activity.onClick(view)

        verify(devicePolicyManager).removeActiveAdmin(componentName)
    }

    // ── Reboot screen scenario ───────────────────────────────────

    @Test
    fun `onResume with reboot required shows reboot layout`() {
        RebootNotificationService.rebootRequired.set(true)

        activity.onResume()

        verify(activity).setContentView(R.layout.reboot_now)
    }

    @Test
    fun `onResume without reboot required shows main layout`() {
        RebootNotificationService.rebootRequired.set(false)

        activity.onResume()

        verify(activity).setContentView(R.layout.activity_main)
    }

    @Test
    fun `reboot state resets after tearDown`() {
        RebootNotificationService.rebootRequired.set(true)
        assertTrue(RebootNotificationService.rebootRequired.get())

        RebootNotificationService.rebootRequired.set(false)
        assertFalse(RebootNotificationService.rebootRequired.get())
    }

    // ── onActivityResult ─────────────────────────────────────────

    @Test
    fun `onActivityResult with RESULT_OK starts UART service`() {
        activity.onActivityResult(12345, Activity.RESULT_OK, null)

        verify(activity).startActivity(any(Intent::class.java))
    }

    @Test
    fun `onActivityResult with RESULT_CANCELED does not start service`() {
        activity.onActivityResult(12345, Activity.RESULT_CANCELED, null)

        verify(activity, never()).startActivity(any(Intent::class.java))
    }

    @Test
    fun `onActivityResult with wrong requestCode is ignored`() {
        activity.onActivityResult(99999, Activity.RESULT_OK, null)

        verify(activity, never()).startActivity(any(Intent::class.java))
    }

    // ── Companion state ──────────────────────────────────────────

    @Test
    fun `isVisible starts false and toggles correctly`() {
        MainActivity.isVisible.set(false)
        assertFalse(MainActivity.isVisible.get())

        MainActivity.isVisible.set(true)
        assertTrue(MainActivity.isVisible.get())

        MainActivity.isVisible.set(false)
        assertFalse(MainActivity.isVisible.get())
    }

    // ── Layout resource IDs ──────────────────────────────────────

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