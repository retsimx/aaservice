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
import com.air.advantage.aaservice.service.daemon.DaemonLifecycle
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
 * Robolectric acceptance: prefs + Intent-extra mode sync via [ModeSwitchCoordinator]
 * in [UartForegroundService].
 *
 * Inject [UartForegroundService.preferencesManager],
 * [UartForegroundService.mailboxWsClientFactory], and
 * [UartForegroundService.daemonLifecycle] **before** the first
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
        TransportStatusStore.reset()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
        TransportStatusStore.reset()
    }

    private fun enableDeviceAdmin() {
        val dpm = service.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowOf(dpm).setActiveAdmin(ComponentName(service, DeviceAdminReceiver::class.java))
    }

    /**
     * Must run before the first onStartCommand that builds the router / coordinator.
     */
    private fun injectTransportFakes(mode: TransportMode) {
        prefs = PreferencesManager(service)
        prefs.transportMode = mode
        fakeWs = FakeMailboxWsClient().apply { emitConnectedOnConnect = true }
        service.preferencesManager = prefs
        service.mailboxWsClientFactory = MailboxWsClientFactory { fakeWs }
        service.daemonLifecycle = object : DaemonLifecycle {
            override fun start(): Boolean = true
            override fun stop(): Boolean = true
            override fun status(): Boolean = true
        }
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
        assertEquals(ModeSwitchStatus.Connected, TransportStatusStore.status.value)
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
        assertEquals(ModeSwitchStatus.Connected, TransportStatusStore.status.value)
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
        assertEquals(ModeSwitchStatus.Idle, TransportStatusStore.status.value)
        assertEquals(
            ServiceHelper.ACTION_REQUEST_PERMISSION,
            nextScheduledAlarmAction(),
        )

        // Accessory path allowed again (same shadow USB path as USB-mode tests).
        ServiceHelper.cancelScheduledServiceStart(service, ServiceHelper.ACTION_REQUEST_PERMISSION)
        openUsbAccessoryPath()

        assertTrue(service.deviceOpen.get())
        assertNotNull(service.uartDataSource)
    }

    // ── Intent extra wins over prefs ─────────────────────────────

    @Test
    fun `TRANSPORT_MODE_CHANGED intent extra ws switches even when prefs were usb`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Usb)
        openUsbAccessoryPath()
        assertTrue(service.deviceOpen.get())
        assertEquals(TransportMode.Usb, prefs.transportMode)

        ServiceHelper.cancelScheduledServiceStart(service, ServiceHelper.ACTION_OPEN_DEVICE)

        // Prefs still Usb; valid extra "ws" writes prefs and switches.
        val result = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws"),
            0,
            1,
        )

        assertEquals(Service.START_STICKY, result)
        assertEquals(TransportMode.Ws, prefs.transportMode)
        assertFalse("extra ws must close USB even when prefs were usb", service.deviceOpen.get())
        assertEquals(1, fakeWs.connectCalls)
        assertEquals(TransportMode.Ws, service.transportRouter.activeMode)
        assertEquals(ModeSwitchStatus.Connected, TransportStatusStore.status.value)
    }

    @Test
    fun `TRANSPORT_MODE_CHANGED daemon_ws_url extra persists before switch`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Usb)
        val customUrl = "ws://10.0.0.5:2026/v1/mailbox-stream"
        assertEquals(PreferencesManager.DEFAULT_DAEMON_WS_URL, prefs.daemonWsUrl)

        val result = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws")
                .putExtra(ServiceHelper.EXTRA_DAEMON_WS_URL, customUrl),
            0,
            1,
        )

        assertEquals(Service.START_STICKY, result)
        assertEquals(customUrl, prefs.daemonWsUrl)
        assertEquals(TransportMode.Ws, prefs.transportMode)
        assertEquals(1, fakeWs.connectCalls)
        assertEquals(TransportMode.Ws, service.transportRouter.activeMode)
    }

    @Test
    fun `TRANSPORT_MODE_CHANGED without mode extra keeps prefs only`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Usb)
        openUsbAccessoryPath()
        assertTrue(service.deviceOpen.get())

        ServiceHelper.cancelScheduledServiceStart(service, ServiceHelper.ACTION_OPEN_DEVICE)

        val result = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED),
            0,
            1,
        )

        assertEquals(Service.START_STICKY, result)
        assertEquals(TransportMode.Usb, prefs.transportMode)
        assertTrue("absent mode extra must keep USB open", service.deviceOpen.get())
        assertEquals(0, fakeWs.connectCalls)
        assertEquals(TransportMode.Usb, service.transportRouter.activeMode)
    }

    // ── Error retry (VERIFY H1): same-mode ws after Magisk fail ───

    @Test
    fun `Magisk fail then second TRANSPORT_MODE_CHANGED ws retries daemon start`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Usb)
        prefs.daemonWsUrl = "ws://127.0.0.1:2026/v1/mailbox-stream"

        var startCalls = 0
        var startResult = false
        service.daemonLifecycle = object : DaemonLifecycle {
            override fun start(): Boolean {
                startCalls++
                return startResult
            }
            override fun stop(): Boolean = true
            override fun status(): Boolean = true
        }

        val first = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws"),
            0,
            1,
        )
        assertEquals(Service.START_STICKY, first)
        assertEquals(1, startCalls)
        assertEquals(TransportMode.Ws, service.transportRouter.activeMode)
        assertEquals(ModeSwitchStatus.Error, TransportStatusStore.status.value)
        assertEquals(0, fakeWs.connectCalls)

        // Operator retry: same mode extra — must not no-op.
        startResult = true
        fakeWs.emitConnectedOnConnect = true

        val second = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws"),
            0,
            1,
        )
        assertEquals(Service.START_STICKY, second)
        assertEquals(2, startCalls)
        assertEquals(1, fakeWs.connectCalls)
        assertEquals(ModeSwitchStatus.Connected, TransportStatusStore.status.value)
        assertEquals(TransportMode.Ws, service.transportRouter.activeMode)
    }

    @Test
    fun `healthy Connected second TRANSPORT_MODE_CHANGED ws does not double connect`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Ws)
        prefs.daemonWsUrl = "ws://127.0.0.1:2026/v1/mailbox-stream"

        var startCalls = 0
        service.daemonLifecycle = object : DaemonLifecycle {
            override fun start(): Boolean {
                startCalls++
                return true
            }
            override fun stop(): Boolean = true
            override fun status(): Boolean = true
        }

        service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws"),
            0,
            1,
        )
        assertEquals(1, startCalls)
        assertEquals(1, fakeWs.connectCalls)
        assertEquals(ModeSwitchStatus.Connected, TransportStatusStore.status.value)

        service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws"),
            0,
            1,
        )
        assertEquals(1, startCalls)
        assertEquals(1, fakeWs.connectCalls)
        assertEquals(ModeSwitchStatus.Connected, TransportStatusStore.status.value)
    }
}
