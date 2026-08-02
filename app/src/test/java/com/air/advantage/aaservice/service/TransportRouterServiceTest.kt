package com.air.advantage.aaservice.service

import android.app.AlarmManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import androidx.preference.PreferenceManager
import com.air.advantage.aaservice.data.mailbox.FakeMailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.receiver.DeviceAdminReceiver
import com.air.advantage.aaservice.util.PreferencesManager
import com.air.advantage.aaservice.util.ServiceHelper
import com.air.advantage.aaservice.util.TransportMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow.extract
import org.robolectric.shadows.ShadowContextImpl

/**
 * Robolectric acceptance: prefs-driven [TransportRouter] in [UartForegroundService].
 *
 * Inject [UartForegroundService.preferencesManager] and
 * [UartForegroundService.mailboxWsClientFactory] **before** the first
 * [UartForegroundService.onStartCommand] that syncs mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TransportRouterServiceTest {

    private lateinit var controller: org.robolectric.android.controller.ServiceController<UartForegroundService>
    private lateinit var service: UartForegroundService
    private lateinit var prefs: PreferencesManager
    private lateinit var fakeWs: FakeMailboxWsClient

    @Before
    fun setUp() {
        controller = Robolectric.buildService(UartForegroundService::class.java)
        service = controller.create().get()
        UartForegroundService.instance = service
        PreferenceManager.getDefaultSharedPreferences(service).edit().clear().apply()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
    }

    private fun enableDeviceAdmin() {
        val dpm = service.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowOf(dpm).setActiveAdmin(ComponentName(service, DeviceAdminReceiver::class.java))
    }

    /**
     * Must run before the first onStartCommand that builds the router.
     */
    private fun injectTransportFakes(mode: TransportMode) {
        prefs = PreferencesManager(service)
        prefs.transportMode = mode
        fakeWs = FakeMailboxWsClient()
        service.preferencesManager = prefs
        service.mailboxWsClientFactory = MailboxWsClientFactory { fakeWs }
    }

    private fun attachAccessory(): UsbAccessory {
        val usbManager = service.getSystemService(Context.USB_SERVICE) as UsbManager
        val accessory = mock(UsbAccessory::class.java)
        shadowOf(usbManager).setAttachedUsbAccessory(accessory)
        return accessory
    }

    private fun grantPermission(accessory: UsbAccessory) {
        val usbManager = service.getSystemService(Context.USB_SERVICE) as UsbManager
        shadowOf(usbManager).grantPermission(accessory)
    }

    private fun injectUsbManager(usbManager: UsbManager) {
        val shadowContext = extract<ShadowContextImpl>(service.baseContext)
        shadowContext.setSystemService(Context.USB_SERVICE, usbManager)
    }

    private fun nextScheduledAlarmAction(): String? {
        val alarmManager = service.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = shadowOf(alarmManager).nextScheduledAlarm
        return if (alarm == null) null else shadowOf(alarm.operation).savedIntent.action
    }

    private fun openUsbAccessoryPath() {
        val accessory = attachAccessory()
        grantPermission(accessory)
        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )
        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_OPEN_DEVICE), 0, 1
        )
    }

    // ── 1. WS + accessory: never openAccessory ───────────────────

    @Test
    fun `prefs ws with accessory attached never calls openAccessory`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Ws)

        val accessory = mock(UsbAccessory::class.java)
        val manager = mock<UsbManager>()
        doReturn(arrayOf(accessory)).whenever(manager).accessoryList
        doReturn(true).whenever(manager).hasPermission(accessory)
        injectUsbManager(manager)

        val requestResult = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )
        val openResult = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_OPEN_DEVICE), 0, 1
        )

        assertEquals(Service.START_STICKY, requestResult)
        assertEquals(Service.START_STICKY, openResult)
        verify(manager, never()).openAccessory(any())
        assertFalse(service.deviceOpen.get())
        assertNull(service.uartDataSource)
        assertEquals(TransportMode.Ws, service.transportRouter.activeMode)
        assertEquals(1, fakeWs.connectCalls)
        assertEquals(0, fakeWs.disconnectCalls)
    }

    // ── 2. USB mode: stock open path ─────────────────────────────

    @Test
    fun `prefs usb opens accessory and builds UART data source`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Usb)

        openUsbAccessoryPath()

        assertTrue("USB mode must open device", service.deviceOpen.get())
        assertNotNull("UART data source must be constructed", service.uartDataSource)
        assertEquals(TransportMode.Usb, service.transportRouter.activeMode)
        assertEquals(0, fakeWs.connectCalls)
    }

    // ── 3. Mode change usb → ws ──────────────────────────────────

    @Test
    fun `mode change usb to ws closes USB and connects mailbox client`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Usb)

        openUsbAccessoryPath()
        assertTrue(service.deviceOpen.get())
        assertNotNull(service.uartDataSource)
        assertEquals(0, fakeWs.connectCalls)

        ServiceHelper.cancelScheduledServiceStart(service, ServiceHelper.ACTION_OPEN_DEVICE)
        prefs.transportMode = TransportMode.Ws

        val result = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws"),
            0,
            1,
        )

        assertEquals(Service.START_STICKY, result)
        assertFalse("USB path must close on switch to WS", service.deviceOpen.get())
        assertNull(service.uartDataSource)
        assertEquals(TransportMode.Ws, service.transportRouter.activeMode)
        assertEquals(1, fakeWs.connectCalls)
        assertEquals(0, fakeWs.disconnectCalls)
    }

    // ── 4. Mode change ws → usb ──────────────────────────────────

    @Test
    fun `mode change ws to usb disconnects client and allows accessory path`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Ws)

        service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws"),
            0,
            1,
        )
        assertEquals(1, fakeWs.connectCalls)
        assertEquals(TransportMode.Ws, service.transportRouter.activeMode)

        prefs.transportMode = TransportMode.Usb

        val result = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "usb"),
            0,
            1,
        )

        assertEquals(Service.START_STICKY, result)
        assertEquals(1, fakeWs.disconnectCalls)
        assertEquals(TransportMode.Usb, service.transportRouter.activeMode)
        assertEquals(
            ServiceHelper.ACTION_REQUEST_PERMISSION,
            nextScheduledAlarmAction(),
        )

        // Accessory path allowed again: mock open succeeds and builds data source.
        val accessory = mock(UsbAccessory::class.java)
        val pfd = mock<ParcelFileDescriptor>()
        val fd = mock<java.io.FileDescriptor>()
        whenever(pfd.fileDescriptor).thenReturn(fd)
        val manager = mock<UsbManager>()
        doReturn(arrayOf(accessory)).whenever(manager).accessoryList
        doReturn(true).whenever(manager).hasPermission(accessory)
        doReturn(pfd).whenever(manager).openAccessory(accessory)
        injectUsbManager(manager)

        ServiceHelper.cancelScheduledServiceStart(service, ServiceHelper.ACTION_REQUEST_PERMISSION)
        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )
        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_OPEN_DEVICE), 0, 1
        )

        verify(manager).openAccessory(accessory)
        assertTrue(service.deviceOpen.get())
        assertNotNull(service.uartDataSource)
    }

    // ── Prefs win over Intent extra ──────────────────────────────

    @Test
    fun `TRANSPORT_MODE_CHANGED intent extra ws does not switch when prefs remain usb`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Usb)
        openUsbAccessoryPath()
        assertTrue(service.deviceOpen.get())

        ServiceHelper.cancelScheduledServiceStart(service, ServiceHelper.ACTION_OPEN_DEVICE)

        // Prefs still Usb; extra "ws" is log-only — same-mode no-op, USB stays open.
        val result = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws"),
            0,
            1,
        )

        assertEquals(Service.START_STICKY, result)
        assertTrue("prefs Usb must keep USB open despite extra ws", service.deviceOpen.get())
        assertEquals(0, fakeWs.connectCalls)
        assertEquals(TransportMode.Usb, service.transportRouter.activeMode)
    }
}
