package com.air.advantage.aaservice.service

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.air.advantage.aaservice.receiver.AlertDialogReceiver
import com.air.advantage.aaservice.ui.usb.UsbConnectActivity
import com.air.advantage.aaservice.util.ServiceHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow.extract
import org.robolectric.shadows.ShadowContextImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OpenAccessoryTest {
    private lateinit var controller: org.robolectric.android.controller.ServiceController<UartForegroundService>
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        controller = Robolectric.buildService(UartForegroundService::class.java)
        service = controller.create().get()
        UartForegroundService.instance = service
        AlertDialogReceiver.alertActive.set(false)
        service.getSharedPreferences(prefsName(), Context.MODE_PRIVATE).edit().clear().apply()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
        AlertDialogReceiver.alertActive.set(false)
        RebootNotificationService.rebootRequired.set(false)
    }

    private fun prefsName(): String = service.packageName + "_preferences"

    private fun crashCount(): Int {
        return service.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .getInt("crash_count", 0)
    }

    private fun injectUsbManager(usbManager: UsbManager) {
        val shadowContext = extract<ShadowContextImpl>(service.baseContext)
        shadowContext.setSystemService(Context.USB_SERVICE, usbManager)
    }

    private fun broadcastActions(): List<String> {
        val app = service.application as android.app.Application
        return shadowOf(app).broadcastIntents.mapNotNull { it.action }
    }

    @Test
    fun `null PFD persists crash_count and stops self and finishes UsbConnectActivity`() {
        RebootNotificationService.rebootRequired.set(true)
        val activityController = Robolectric.buildActivity(UsbConnectActivity::class.java).setup()
        val activity = activityController.get()
        assertFalse(activity.isFinishing)

        val manager = mock<UsbManager>()
        val accessory = mock<UsbAccessory>()
        doReturn(null).whenever(manager).openAccessory(accessory)
        injectUsbManager(manager)

        val result = service.openAccessory(accessory)

        assertFalse(result)
        assertEquals("crash_count should be persisted", 1, crashCount())
        assertTrue("service should stopSelf", shadowOf(service).isStoppedBySelf)
        assertTrue("UsbConnectActivity should be finished", activity.isFinishing)
        assertFalse(broadcastActions().contains(ServiceHelper.ACTION_REBOOT_DEVICE))

        activityController.destroy()
    }

    @Test
    fun `null PFD with crash_count above threshold sends reboot broadcast`() {
        val manager = mock<UsbManager>()
        val accessory = mock<UsbAccessory>()
        doReturn(null).whenever(manager).openAccessory(accessory)
        injectUsbManager(manager)

        service.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .edit().putInt("crash_count", 6).apply()

        val result = service.openAccessory(accessory)

        assertFalse(result)
        assertTrue(
            "reboot broadcast should be sent",
            broadcastActions().contains(ServiceHelper.ACTION_REBOOT_DEVICE),
        )
        assertTrue("service should stopSelf", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `already working guard returns true without reopening`() {
        val manager = mock<UsbManager>()
        val accessory = mock<UsbAccessory>()
        val pfd = mock<ParcelFileDescriptor>()
        val fd = mock<java.io.FileDescriptor>()
        whenever(pfd.fileDescriptor).thenReturn(fd)
        whenever(manager.openAccessory(accessory)).thenReturn(pfd)
        injectUsbManager(manager)

        assertTrue(service.openAccessory(accessory))
        assertTrue(service.openAccessory(accessory))

        verify(manager, times(1)).openAccessory(accessory)
    }

    @Test
    fun `success path opens accessory and closes stored PFD on destroy`() {
        val manager = mock<UsbManager>()
        val accessory = mock<UsbAccessory>()
        val pfd = mock<ParcelFileDescriptor>()
        val fd = mock<java.io.FileDescriptor>()
        whenever(pfd.fileDescriptor).thenReturn(fd)
        whenever(manager.openAccessory(accessory)).thenReturn(pfd)
        injectUsbManager(manager)

        val result = service.openAccessory(accessory)

        assertTrue(result)
        verify(manager).openAccessory(accessory)
        assertNotNull(service.uartDataSource)

        service.onDestroy()

        verify(pfd).close()
    }

    @Test
    fun `UART close-down resets deviceOpen and already working guard so accessory can reopen`() {
        val manager = mock<UsbManager>()
        val accessory = mock<UsbAccessory>()
        val pfd = mock<ParcelFileDescriptor>()
        val fd = mock<java.io.FileDescriptor>()
        whenever(pfd.fileDescriptor).thenReturn(fd)
        whenever(manager.openAccessory(accessory)).thenReturn(pfd)
        injectUsbManager(manager)

        assertTrue(service.openAccessory(accessory))
        // mirrors onStartCommand OPEN_DEVICE success path
        service.deviceOpen.set(true)
        assertTrue(service.deviceOpen.get())

        // Simulate UART close-down: the read flow stops -> invokeOnCompletion runs closeUartIo()
        runBlocking {
            service.uartIoJob?.cancel()
            service.uartIoJob?.join()
            // closeUartIo() runs on the read coroutine's thread via invokeOnCompletion;
            // await its completion invariant (last statement in closeUartIo) deterministically.
            withTimeout(5000) {
                while (service.uartDataSource != null) delay(10)
            }
        }

        assertFalse("close-down must clear deviceOpen", service.deviceOpen.get())

        // stored PFD was cleared, so the already-working guard no longer short-circuits
        assertTrue(service.openAccessory(accessory))
        verify(manager, times(2)).openAccessory(accessory)
    }
}
