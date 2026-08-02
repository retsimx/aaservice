package com.air.advantage.aaservice.service

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.util.ServiceHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UartForegroundServiceTest {

    private lateinit var service: UartForegroundService
    private lateinit var controller: org.robolectric.android.controller.ServiceController<UartForegroundService>

    @Before
    fun setUp() {
        controller = Robolectric.buildService(UartForegroundService::class.java)
        service = spy(controller.get())

        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        service.onDestroy()
        UartForegroundService.instance = null
    }

    private fun sentBroadcasts(): List<Intent> =
        shadowOf(RuntimeEnvironment.getApplication() as ContextWrapper).broadcastIntents

    // ── startUartIo ──────────────────────────────────────────────

    @Test
    fun `startUartIo creates data source and launches read job`() {
        val pfd = mock<ParcelFileDescriptor>()
        val fd = mock<java.io.FileDescriptor>()
        whenever(pfd.fileDescriptor).thenReturn(fd)

        service.startUartIo(pfd)

        assertNotNull(service.uartDataSource)
    }

    // ── engine delegation: direct queue ──────────────────────────

    @Test
    fun `enqueueUartMessage feeds the engine direct queue`() {
        service.enqueueUartMessage("Temperature")
        val frame = service.dispatchEngine.onPing()
        assertNotNull(frame)
        val expectedCrc = CrcCalculator.computeHex("Temperature")
        assertEquals("<U>Temperature</U=$expectedCrc>", String(frame!!, Charsets.UTF_8))
    }

    @Test
    fun `enqueueUartMessage with query strips nothing from direct message`() {
        service.enqueueUartMessage("getZoneData?zone=3")
        val frame = service.dispatchEngine.onPing()
        assertNotNull(frame)
        val expectedCrc = CrcCalculator.computeHex("getZoneData?zone=3")
        assertEquals("<U>getZoneData?zone=3</U=$expectedCrc>", String(frame!!, Charsets.UTF_8))
    }

    @Test
    fun `requestSinglePoll feeds the engine direct queue with raw tag`() {
        service.requestSinglePoll("getClock")
        val frame = service.dispatchEngine.onPing()
        assertNotNull(frame)
        val expectedCrc = CrcCalculator.computeHex("getClock")
        assertEquals("<U>getClock</U=$expectedCrc>", String(frame!!, Charsets.UTF_8))
    }

    // ── engine delegation: CAN queue ─────────────────────────────

    @Test
    fun `enqueueCanIds feeds the engine CAN queue`() {
        service.enqueueCanIds("1 2 3")
        // First ping arms CAN-wanted and returns the poll entry.
        service.dispatchEngine.onPing()
        // Second ping prefers the CAN branch and emits setCAN with the queued ids.
        val frame = service.dispatchEngine.onPing()
        assertNotNull(frame)
        val text = String(frame!!, Charsets.UTF_8)
        assertTrue(text.startsWith("<U>setCAN "))
        assertTrue(text.contains("1"))
        assertTrue(text.contains("2"))
        assertTrue(text.contains("3"))
    }

    @Test
    fun `processCanIds feeds the engine CAN queue`() {
        service.processCanIds("5 6 7")
        service.dispatchEngine.onPing()
        val frame = service.dispatchEngine.onPing()
        assertNotNull(frame)
        val text = String(frame!!, Charsets.UTF_8)
        assertTrue(text.startsWith("<U>setCAN "))
        assertTrue(text.contains("5"))
        assertTrue(text.contains("7"))
    }

    @Test
    fun `enqueueCanIds ignores non-numeric tokens`() {
        service.enqueueCanIds("1 abc 2")
        service.dispatchEngine.onPing()
        val frame = service.dispatchEngine.onPing()
        assertNotNull(frame)
        val text = String(frame!!, Charsets.UTF_8)
        assertTrue(text.contains("1"))
        assertTrue(text.contains("2"))
        assertFalse(text.contains("abc"))
    }

    // ── uartEventSink: onPollData ────────────────────────────────

    @Test
    fun `uartEventSink onPollData writes payload to dataCache`() {
        val payload = "<request>getClock</request><time>t</time>".toByteArray()
        service.uartEventSink.onPollData("getClock", payload)
        assertArrayEquals(payload, service.dataCache.get("getClock"))
    }

    @Test
    fun `uartEventSink onPollData replaces previously cached payload`() {
        service.uartEventSink.onPollData("getClock", "a".toByteArray())
        service.uartEventSink.onPollData("getClock", "b".toByteArray())
        assertArrayEquals("b".toByteArray(), service.dataCache.get("getClock"))
    }

    @Test
    fun `uartEventSink onPollData triggers broadcastData`() {
        service.deviceOpen.set(true)
        service.uartEventSink.onPollData("getClock", "12:00".toByteArray())
        val sent = sentBroadcasts()
        assertTrue(sent.any {
            it.action == "com.air.advantage.MESSAGE_FROM_CB" &&
                it.getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "getClock"
        })
    }

    // ── uartEventSink: onRawCan (reference handleGetCan path) ────

    @Test
    fun `uartEventSink onRawCan broadcasts secure and encrypted no-permission`() {
        val payload = "getCAN 1026".toByteArray()
        service.uartEventSink.onRawCan(payload)

        val sent = sentBroadcasts()
        assertTrue(sent.any {
            it.action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" &&
                it.getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "rawCan" &&
                it.getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE") == "getCAN 1026"
        })
        assertTrue(sent.any { it.action == "com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST" })
    }

    @Test
    fun `handleGetCan broadcasts for each raw CAN payload`() {
        service.handleGetCan("getCAN 1026")
        service.handleGetCan("getCAN 1026")
        val sent = sentBroadcasts()
        assertEquals(2, sent.count { it.action == "com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST" })
        assertEquals(2, sent.count { it.action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" })
    }

    @Test
    fun `handleGetCan forwards different consecutive payloads`() {
        service.handleGetCan("getCAN 1")
        service.handleGetCan("getCAN 2")
        val sent = sentBroadcasts()
        assertEquals(2, sent.count { it.action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" })
        assertEquals(2, sent.count { it.action == "com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST" })
    }

    @Test
    fun `handleGetCan encrypts the no-permission payload`() {
        service.handleGetCan("getCAN 1026")
        val sent = sentBroadcasts()
        val noPerm = sent.filter { it.action == "com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST" }
        assertEquals(1, noPerm.size)
        val extra = noPerm[0].getByteArrayExtra("com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST")
        assertNotNull(extra)
        assertFalse(extra!!.contentEquals("getCAN 1026".toByteArray()))
    }

    @Test
    fun `handleGetCan uses Fujitsu actions on Fujitsu variant`() {
        doReturn("com.air.advantage.fgassist").whenever(service).packageName

        service.handleGetCan("getCAN 1026")

        verify(service, times(1)).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU"
        }, anyOrNull())
        verify(service, times(1)).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST"
        })
    }

    // ── onCreate ─────────────────────────────────────────────────

    @Test
    fun `onCreate sets instance to service`() {
        UartForegroundService.instance = null
        service.onCreate()
        assertEquals(service, UartForegroundService.instance)
    }

    @Test
    fun `onCreate registers all 10 receivers`() {
        service.onCreate()
        assertEquals(10, service.registeredReceivers.size)
        verify(service, times(7)).registerReceiver(any(), any())
        verify(service, times(3)).registerReceiver(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `onCreate registers USB permission receiver`() {
        service.onCreate()
        assertTrue(service.registeredReceivers.any { it.javaClass.simpleName == "UsbPermissionReceiver" })
    }

    @Test
    fun `onCreate registers data receivers`() {
        service.onCreate()
        val names = service.registeredReceivers.map { it.javaClass.simpleName }
        assertTrue(names.contains("GetDataReceiver"))
        assertTrue(names.contains("GetAllDataReceiver"))
        assertTrue(names.contains("MessageToCbReceiver"))
    }

    @Test
    fun `onCreate registers secure broadcast receivers`() {
        service.onCreate()
        val names = service.registeredReceivers.map { it.javaClass.simpleName }
        assertTrue(names.contains("CanToCbReceiver"))
        assertTrue(names.contains("BroadcastCanToCbReceiver"))
        assertTrue(names.contains("BackupMessageReceiver"))
    }

    @Test
    fun `onCreate registers no-permission receivers`() {
        service.onCreate()
        val names = service.registeredReceivers.map { it.javaClass.simpleName }
        assertTrue(names.contains("CanToCbNoPermissionReceiver"))
        assertTrue(names.contains("BroadcastCanToCbNoPermissionReceiver"))
        assertTrue(names.contains("BackupMessageNoPermissionReceiver"))
    }

    // ── onDestroy ────────────────────────────────────────────────

    @Test
    fun `onDestroy clears instance`() {
        UartForegroundService.instance = service
        service.deviceOpen.set(true)
        service.onDestroy()
        assertNull(UartForegroundService.instance)
        assertFalse(service.deviceOpen.get())
    }

    @Test
    fun `onDestroy unregisters all receivers`() {
        service.onCreate()
        service.onDestroy()
        verify(service, times(10)).unregisterReceiver(any())
    }

    // ── onBind ───────────────────────────────────────────────────

    @Test
    fun `onBind returns null`() {
        assertNull(service.onBind(null))
    }

    // ── openAccessory ────────────────────────────────────────────

    @Test
    fun `openAccessory with crash count below threshold opens accessory`() {
        val manager = mock<UsbManager>()
        val accessory = mock<UsbAccessory>()
        val pfd = mock<ParcelFileDescriptor>()
        val fd = mock<java.io.FileDescriptor>()
        whenever(pfd.fileDescriptor).thenReturn(fd)
        whenever(manager.openAccessory(accessory)).thenReturn(pfd)
        doReturn(manager).whenever(service).getSystemService(Context.USB_SERVICE)

        val result = service.openAccessory(accessory)

        assertTrue(result)
        verify(manager).openAccessory(accessory)
        assertNotNull(service.uartDataSource)
        assertTrue(service.deviceOpen.get())
    }

    @Test
    fun `openAccessory with null pfd returns false and device stays closed`() {
        val manager = mock<UsbManager>()
        val accessory = mock<UsbAccessory>()
        whenever(manager.openAccessory(accessory)).thenReturn(null)
        doReturn(manager).whenever(service).getSystemService(Context.USB_SERVICE)

        val result = service.openAccessory(accessory)

        assertFalse(result)
        assertFalse(service.deviceOpen.get())
    }

    @Test
    fun `onAccessoryDetached closes device`() {
        service.deviceOpen.set(true)

        service.onAccessoryDetached()

        assertFalse(service.deviceOpen.get())
    }

    @Test
    fun `openAccessory with null PFD and crash count above threshold sends reboot broadcast`() {
        val manager = mock<UsbManager>()
        val accessory = mock<UsbAccessory>()
        doReturn(manager).whenever(service).getSystemService(Context.USB_SERVICE)

        val prefs = service.getSharedPreferences(
            service.packageName + "_preferences", Context.MODE_PRIVATE
        )
        prefs.edit().putInt("crash_count", 6).apply()

        val result = service.openAccessory(accessory)

        assertFalse(result)
        verify(service).sendBroadcast(argThat<Intent> {
            action == ServiceHelper.ACTION_REBOOT_DEVICE
        })
    }

    @Test
    fun `openAccessory with null UsbManager returns false`() {
        val accessory = mock<UsbAccessory>()
        doReturn(null).whenever(service).getSystemService(Context.USB_SERVICE)

        val result = service.openAccessory(accessory)

        assertFalse(result)
    }

    // ── showNotification ─────────────────────────────────────────

    @Test
    fun `showNotification with connected true shows Connected title`() {
        val ctrl = Robolectric.buildService(UartForegroundService::class.java).create()
        val realService = ctrl.get()
        realService.showNotification(connected = true)

        val shadowService = shadowOf(realService)
        assertEquals(1234, shadowService.lastForegroundNotificationId)
        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(
            "Connected to your system",
            notification?.extras?.getString("android.title")
        )

        realService.onDestroy()
    }

    @Test
    fun `showNotification with connected false shows Not connected title`() {
        val ctrl = Robolectric.buildService(UartForegroundService::class.java).create()
        val realService = ctrl.get()
        realService.showNotification(connected = false)

        val shadowService = shadowOf(realService)
        assertEquals(1234, shadowService.lastForegroundNotificationId)
        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(
            "Not connected to your system",
            notification?.extras?.getString("android.title")
        )

        realService.onDestroy()
    }

    @Test
    fun `showNotification with reboot required shows Reboot required title`() {
        RebootNotificationService.rebootRequired.set(true)
        try {
            val ctrl = Robolectric.buildService(UartForegroundService::class.java).create()
            val realService = ctrl.get()
            realService.showNotification(connected = true)

            val shadowService = shadowOf(realService)
            assertEquals(1234, shadowService.lastForegroundNotificationId)
            val notification = shadowService.lastForegroundNotification
            assertNotNull(notification)
            assertEquals(
                "Reboot required",
                notification?.extras?.getString("android.title")
            )

            realService.onDestroy()
        } finally {
            RebootNotificationService.rebootRequired.set(false)
        }
    }

    @Test
    fun `showNotification duplicate call does not repeat side effects`() {
        val app = service.application as android.app.Application
        shadowOf(app).clearBroadcastIntents()

        service.showNotification(connected = true)
        service.showNotification(connected = true)

        val hideWarnings = shadowOf(app).broadcastIntents.count {
            it.action == "com.air.advantage.HIDE_WARNING"
        }
        assertEquals(1, hideWarnings)
    }

    @Test
    fun `showNotification with different titles shows new notification`() {
        val ctrl = Robolectric.buildService(UartForegroundService::class.java).create()
        val realService = ctrl.get()
        realService.showNotification(connected = true)
        realService.showNotification(connected = false)

        val shadowService = shadowOf(realService)
        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(
            "Not connected to your system",
            notification?.extras?.getString("android.title")
        )

        realService.onDestroy()
    }

    // ── broadcastData ──────────────────────────────────────────

    @Test
    fun `broadcastData with tag not in cache sends no broadcasts`() {
        service.deviceOpen.set(true)
        service.broadcastData("nonExistentTag")
        verify(service, never()).sendBroadcast(any<Intent>())
    }

    @Test
    fun `broadcastData with device not open sends no broadcasts`() {
        val tag = "getSystemData"
        service.dataCache.put(tag, "systemData".toByteArray())

        service.broadcastData(tag)

        verify(service, never()).sendBroadcast(any<Intent>())
        verify(service, never()).sendBroadcast(any<Intent>(), anyOrNull())
    }

    @Test
    fun `broadcastData with open device and tag in cache sends one plain broadcast`() {
        val tag = "getSystemData"
        val data = "systemData".toByteArray()
        service.dataCache.put(tag, data)
        service.deviceOpen.set(true)

        service.broadcastData(tag)

        verify(service, times(1)).sendBroadcast(any<Intent>())
        verify(service, never()).sendBroadcast(any<Intent>(), anyOrNull())
    }

    @Test
    fun `broadcastData never sends secure broadcast`() {
        val tag = "getClock"
        val data = "12:00".toByteArray()
        service.dataCache.put(tag, data)
        service.deviceOpen.set(true)

        service.broadcastData(tag)

        verify(service, never()).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" ||
            action == "com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU"
        }, anyOrNull())
    }

    @Test
    fun `broadcastData never sends MESSAGE_FROM_CB_SECURE intent`() {
        val tag = "getZoneData"
        val data = "zone1=22".toByteArray()
        service.dataCache.put(tag, data)
        service.deviceOpen.set(true)

        service.broadcastData(tag)

        verify(service, never()).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_FROM_CB_SECURE"
        }, anyOrNull())
    }

    @Test
    fun `broadcastData sends ByteArray extra on regular intent`() {
        val tag = "getTimers"
        val data = "timer1=on".toByteArray()
        service.dataCache.put(tag, data)
        service.deviceOpen.set(true)

        service.broadcastData(tag)

        verify(service).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_FROM_CB" &&
            getStringExtra("com.air.advantage.GET_DATA_REQUEST") == tag &&
            getByteArrayExtra("com.air.advantage.MESSAGE_FROM_CB")?.contentEquals(data) == true
        })
    }

    @Test
    fun `broadcastData never sends Fujitsu secure broadcast`() {
        val tag = "getSchedules"
        val data = "schedule1".toByteArray()
        service.dataCache.put(tag, data)
        service.deviceOpen.set(true)

        service.broadcastData(tag)

        verify(service, never()).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" ||
                action == "com.air.advantage.MESSAGE_FROM_CB_SECURE_FUJITSU"
        }, anyOrNull())
    }

    // ── periodicInfoBroadcast ──────────────────────────────────

    @Test
    fun `periodicInfoBroadcast sends broadcast after 5 second delay`() = runBlocking {
        val job = launch {
            service.periodicInfoBroadcast()
        }
        delay(5500)
        job.cancel()

        verify(service, atLeastOnce()).sendBroadcast(any<Intent>(), anyOrNull())
    }

    @Test
    fun `periodicInfoBroadcast sends correct GET_DATA_REQUEST extra`() = runBlocking {
        val job = launch {
            service.periodicInfoBroadcast()
        }
        delay(5500)
        job.cancel()

        verify(service).sendBroadcast(argThat<Intent> {
            getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "aaServiceInfo"
        }, anyOrNull())
    }

    @Test
    fun `periodicInfoBroadcast includes version in JSON`() = runBlocking {
        val job = launch {
            service.periodicInfoBroadcast()
        }
        delay(5500)
        job.cancel()

        verify(service).sendBroadcast(argThat<Intent> {
            val json = getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE")
            json?.contains("\"version\"") == true
        }, anyOrNull())
    }

    @Test
    fun `periodicInfoBroadcast includes enabled field in JSON`() = runBlocking {
        val job = launch {
            service.periodicInfoBroadcast()
        }
        delay(5500)
        job.cancel()

        verify(service).sendBroadcast(argThat<Intent> {
            val json = getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE")
            json?.contains("\"enabled\"") == true
        }, anyOrNull())
    }

    @Test
    fun `periodicInfoBroadcast sends encrypted no-permission broadcast`() = runBlocking {
        val job = launch {
            service.periodicInfoBroadcast()
        }
        delay(5500)
        job.cancel()

        verify(service).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_TO_CB_NO_PERMISSION_BROADCAST"
        })
    }
}
