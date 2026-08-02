package com.air.advantage.aaservice.service

import android.app.AlarmManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import androidx.preference.PreferenceManager
import com.air.advantage.aaservice.data.mailbox.FakeMailboxWsClient
import com.air.advantage.aaservice.data.mailbox.MailboxWsClientFactory
import com.air.advantage.aaservice.receiver.DeviceAdminReceiver
import com.air.advantage.aaservice.service.daemon.DaemonLifecycle
import com.air.advantage.aaservice.ui.main.MainActivity
import com.air.advantage.aaservice.util.PreferencesManager
import com.air.advantage.aaservice.util.ServiceHelper
import com.air.advantage.aaservice.util.TransportMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.doReturn
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
class OnStartCommandTest {

    private lateinit var controller: org.robolectric.android.controller.ServiceController<UartForegroundService>
    private lateinit var service: UartForegroundService

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

    /** Inject before any onStartCommand that may switch to WS (avoids real su hang). */
    private fun injectTransportFakes(mode: TransportMode = TransportMode.Usb) {
        val prefs = PreferencesManager(service)
        prefs.transportMode = mode
        service.preferencesManager = prefs
        service.mailboxWsClientFactory = MailboxWsClientFactory {
            FakeMailboxWsClient().apply { emitConnectedOnConnect = true }
        }
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

    private fun broadcastActions(): List<String> {
        val app = service.application as android.app.Application
        return shadowOf(app).broadcastIntents.mapNotNull { it.action }
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

    // ── Stage 1: periodic broadcast thread ───────────────────────

    @Test
    fun `onStartCommand launches periodic broadcast thread once`() {
        enableDeviceAdmin()

        val first = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )

        assertNotNull(service.periodicJob)

        val job = service.periodicJob
        service.onStartCommand(Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1)

        assertSame("periodic job should not be relaunched", job, service.periodicJob)
        assertEquals(Service.START_STICKY, first)
    }

    // ── Stage 2: device-admin check ──────────────────────────────

    @Test
    fun `onStartCommand when device admin not active starts MainActivity and returns START_NOT_STICKY`() {
        val app = service.application as android.app.Application
        shadowOf(app).clearNextStartedActivities()

        val result = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )

        assertEquals(Service.START_NOT_STICKY, result)
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("MainActivity should be started", started)
        assertEquals(MainActivity::class.java.name, started?.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started?.flags)
    }

    // ── Stage 3: null action guard ───────────────────────────────

    @Test
    fun `onStartCommand with null action schedules permission request and stops self`() {
        enableDeviceAdmin()

        val result = service.onStartCommand(Intent(), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue("service should stopSelf", shadowOf(service).isStoppedBySelf)
        assertEquals(ServiceHelper.ACTION_REQUEST_PERMISSION, nextScheduledAlarmAction())
    }

    // ── Stage 4: accessory discovery ─────────────────────────────

    @Test
    fun `onStartCommand with no accessory returns START_STICKY`() {
        enableDeviceAdmin()

        val result = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )

        assertEquals(Service.START_STICKY, result)
        assertEquals(1234, shadowOf(service).lastForegroundNotificationId)
        assertEquals(
            "Not connected to your system",
            shadowOf(service).lastForegroundNotification?.extras?.getString("android.title")
        )
    }

    // ── Stage 5: action dispatch ─────────────────────────────────

    @Test
    fun `onStartCommand REQUEST_PERMISSION with permission granted broadcasts ALLOW_HIDING and schedules open`() {
        enableDeviceAdmin()
        val accessory = attachAccessory()
        grantPermission(accessory)

        val result = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(broadcastActions().contains(ServiceHelper.ACTION_ALLOW_HIDING))
        assertEquals(ServiceHelper.ACTION_OPEN_DEVICE, nextScheduledAlarmAction())
    }

    @Test
    fun `onStartCommand REQUEST_PERMISSION without permission broadcasts BLOCK_HIDING and requests permission`() {
        enableDeviceAdmin()
        attachAccessory()

        val result = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(broadcastActions().contains(ServiceHelper.ACTION_BLOCK_HIDING))
        assertFalse("service should not stop", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `onStartCommand OPEN_DEVICE opens accessory and broadcasts ALLOW_HIDING and marks device open`() {
        enableDeviceAdmin()
        val accessory = attachAccessory()
        grantPermission(accessory)

        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )

        val result = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_OPEN_DEVICE), 0, 1
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(broadcastActions().contains(ServiceHelper.ACTION_ALLOW_HIDING))
        assertFalse("service should not stop after opening", shadowOf(service).isStoppedBySelf)

        // deviceOpen is now true, so a follow-up permission request skips the permission block
        shadowOf(service.application as android.app.Application).clearBroadcastIntents()
        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )
        assertFalse(
            "no permission re-request when device is already open",
            broadcastActions().contains(ServiceHelper.ACTION_ALLOW_HIDING)
        )
        assertFalse(broadcastActions().contains(ServiceHelper.ACTION_BLOCK_HIDING))
    }

    @Test
    fun `onStartCommand OPEN_DEVICE failure returns START_NOT_STICKY and does not broadcast ALLOW_HIDING`() {
        enableDeviceAdmin()
        val accessory = attachAccessory()
        grantPermission(accessory)

        // First pass: discover accessory and store currentAccessory
        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )

        // Force openAccessory to fail by swapping in a mock USB manager
        val manager = mock<UsbManager>()
        doReturn(null).whenever(manager).openAccessory(accessory)
        injectUsbManager(manager)

        shadowOf(service.application as android.app.Application).clearBroadcastIntents()

        val result = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_OPEN_DEVICE), 0, 1
        )

        verify(manager).openAccessory(accessory)
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue("service should stopSelf after failed open", shadowOf(service).isStoppedBySelf)
        assertFalse(broadcastActions().contains(ServiceHelper.ACTION_ALLOW_HIDING))
    }

    @Test
    fun `onStartCommand CLOSE_DEVICE stops self and returns START_NOT_STICKY`() {
        enableDeviceAdmin()
        val accessory = attachAccessory()
        grantPermission(accessory)

        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )

        val result = service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_CLOSE_DEVICE), 0, 1
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue("service should stopSelf", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `onDestroy cancels a pending OPEN_DEVICE alarm`() {
        enableDeviceAdmin()
        val accessory = attachAccessory()
        grantPermission(accessory)

        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )
        assertEquals(ServiceHelper.ACTION_OPEN_DEVICE, nextScheduledAlarmAction())

        service.onDestroy()

        assertNull("OPEN_DEVICE alarm must be cancelled on destroy", nextScheduledAlarmAction())
    }

    // ── TRANSPORT_MODE_CHANGED (A6: extras→prefs then ModeSwitchCoordinator) ─

    @Test
    fun `onStartCommand TRANSPORT_MODE_CHANGED without accessory does not stop or crash`() {
        enableDeviceAdmin()
        injectTransportFakes(TransportMode.Usb)

        val result = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "ws"),
            0,
            1
        )

        assertEquals(Service.START_STICKY, result)
        assertFalse("service must not stopSelf for mode change", shadowOf(service).isStoppedBySelf)
        assertFalse(service.deviceOpen.get())
        assertEquals(TransportMode.Ws, service.transportRouter.activeMode)
    }

    @Test
    fun `onStartCommand TRANSPORT_MODE_CHANGED with accessory does not redirect to permission or close USB`() {
        enableDeviceAdmin()
        val accessory = attachAccessory()
        grantPermission(accessory)

        // Establish open-device state. Prefs remain Usb (default); intent extra "usb"
        // is same-mode — coordinator path is a no-op (USB stays open).
        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_REQUEST_PERMISSION), 0, 1
        )
        service.onStartCommand(
            Intent().setAction(ServiceHelper.ACTION_OPEN_DEVICE), 0, 1
        )
        assertTrue("precondition: device should be open", service.deviceOpen.get())
        // Clear side-effects from the open path so we only observe the mode-change Intent.
        shadowOf(service.application as android.app.Application).clearBroadcastIntents()
        ServiceHelper.cancelScheduledServiceStart(service, ServiceHelper.ACTION_OPEN_DEVICE)
        assertNull(nextScheduledAlarmAction())

        val result = service.onStartCommand(
            Intent(ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED)
                .putExtra(ServiceHelper.EXTRA_TRANSPORT_MODE, "usb"),
            0,
            1
        )

        assertEquals(Service.START_STICKY, result)
        assertFalse("service must not stopSelf for mode change", shadowOf(service).isStoppedBySelf)
        assertTrue("USB must stay open when prefs remain Usb", service.deviceOpen.get())
        assertFalse(
            "must not rewrite to REQUEST_PERMISSION path",
            broadcastActions().contains(ServiceHelper.ACTION_ALLOW_HIDING)
        )
        assertFalse(broadcastActions().contains(ServiceHelper.ACTION_BLOCK_HIDING))
        assertNull(
            "must not schedule OPEN_DEVICE from permission redirect",
            nextScheduledAlarmAction()
        )
    }
}
