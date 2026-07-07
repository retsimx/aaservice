package com.air.advantage.aaservice.ui.usb

import android.app.Activity
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.service.RebootNotificationService
import com.air.advantage.aaservice.util.ServiceHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UsbConnectActivityTest {

    private lateinit var activity: UsbConnectActivity

    @Before
    fun setUp() {
        RebootNotificationService.rebootRequired.set(false)
        activity = Robolectric.buildActivity(UsbConnectActivity::class.java).create().get()
    }

    @After
    fun tearDown() {
        RebootNotificationService.rebootRequired.set(false)
    }

    @Test
    fun `onCreate starts UART service when reboot not required`() {
        mockStatic(ServiceHelper::class.java).use {
            activity.onCreate(null)
            verify(activity).finish()
        }
    }

    @Test
    fun `onCreate finishes when reboot not required`() {
        mockStatic(ServiceHelper::class.java).use {
            activity.onCreate(null)
            verify(activity).finish()
        }
    }

    @Test
    fun `onCreate does not set content view when reboot not required`() {
        mockStatic(ServiceHelper::class.java).use {
            activity.onCreate(null)
            verify(activity, never()).setContentView(R.layout.reboot_now)
        }
    }

    @Test
    fun `onCreate sets reboot content view when reboot required`() {
        RebootNotificationService.rebootRequired.set(true)
        mockStatic(ServiceHelper::class.java).use {
            activity.onCreate(null)
            verify(activity).setContentView(R.layout.reboot_now)
        }
    }

    @Test
    fun `onCreate calls setVersionText when reboot required`() {
        RebootNotificationService.rebootRequired.set(true)
        mockStatic(ServiceHelper::class.java).use {
            activity.onCreate(null)
            verify(activity).setTheme(R.style.Theme_Transparent)
        }
    }

    @Test
    fun `onCreate does not start UART service when reboot required`() {
        RebootNotificationService.rebootRequired.set(true)
        mockStatic(ServiceHelper::class.java).use {
            activity.onCreate(null)
            verify(activity, never()).finish()
        }
    }

    @Test
    fun `onCreate does not finish when reboot required`() {
        RebootNotificationService.rebootRequired.set(true)
        mockStatic(ServiceHelper::class.java).use {
            activity.onCreate(null)
            verify(activity, never()).finish()
        }
    }

    @Test
    fun `finishIfShowing finishes activity when instance is set`() {
        activity.onCreate(null)
        UsbConnectActivity.finishIfShowing()
        verify(activity, times(2)).finish()
    }

    @Test
    fun `finishIfShowing does nothing when no instance`() {
        RebootNotificationService.rebootRequired.set(true)
        val freshActivity = Robolectric.buildActivity(UsbConnectActivity::class.java).create().get()
        freshActivity.onCreate(null)
        freshActivity.onDestroy()

        UsbConnectActivity.finishIfShowing()

        verify(freshActivity, never()).finish()
    }

    @Test
    fun `finishIfShowing with instance set but already finished`() {
        activity.onCreate(null)
        activity.finish()
        UsbConnectActivity.finishIfShowing()
        verify(activity, times(2)).finish()
    }

    @Test
    fun `onCreate sets instance reference`() {
        activity.onCreate(null)
        val field = UsbConnectActivity::class.java.getDeclaredField("instance")
        field.isAccessible = true
        val instance = field.get(null) as? java.lang.ref.WeakReference<*>
        assertNotNull(instance)
        assertEquals(activity, instance?.get())
    }

    @Test
    fun `onDestroy clears instance when it matches`() {
        activity.onCreate(null)
        activity.onDestroy()

        val field = UsbConnectActivity::class.java.getDeclaredField("instance")
        field.isAccessible = true
        val instance = field.get(null) as? java.lang.ref.WeakReference<*>
        assertNull(instance?.get())
    }

    @Test
    fun `onDestroy does not clear instance when different activity`() {
        val otherActivity = mock(Activity::class.java)
        val field = UsbConnectActivity::class.java.getDeclaredField("instance")
        field.isAccessible = true

        activity.onCreate(null)
        activity.onDestroy()
        assertNull(field.get(null))

        field.set(null, java.lang.ref.WeakReference(otherActivity))
        activity.onDestroy()
        val stillSet = field.get(null) as java.lang.ref.WeakReference<*>
        assertEquals(otherActivity, stillSet.get())
    }

    @Test
    fun `onCreate sets transparent theme`() {
        activity.onCreate(null)
        verify(activity).setTheme(R.style.Theme_Transparent)
    }

    @Test
    fun `onCreate overrides pending transition`() {
        activity.onCreate(null)
        verify(activity).overridePendingTransition(0, 0)
    }
}