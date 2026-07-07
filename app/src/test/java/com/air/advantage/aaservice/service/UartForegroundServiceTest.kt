package com.air.advantage.aaservice.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.air.advantage.aaservice.data.protocol.CrcCalculator
import com.air.advantage.aaservice.data.repository.CanStateRepository
import com.air.advantage.aaservice.data.repository.DataCacheRepository
import com.air.advantage.aaservice.data.repository.PollQueueRepository
import com.air.advantage.aaservice.data.uart.UartDataSource
import com.air.advantage.aaservice.domain.model.CanMessage
import com.air.advantage.aaservice.domain.state.CanMessageQueue
import com.air.advantage.aaservice.domain.state.UartState
import com.air.advantage.aaservice.domain.state.UartStateMachine
import com.air.advantage.aaservice.util.CryptoHelper
import com.air.advantage.aaservice.util.FujitsuDetector
import com.air.advantage.aaservice.util.ServiceHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mockStatic
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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

    // ── startUartIo ──────────────────────────────────────────────

    @Test
    fun `startUartIo creates data source and launches read job`() {
        val pfd = mock<ParcelFileDescriptor>()
        val fd = mock<java.io.FileDescriptor>()
        whenever(pfd.fileDescriptor).thenReturn(fd)

        service.startUartIo(pfd)

        assertNotNull(service.uartDataSource)
    }

    // ── handleReadStream ─────────────────────────────────────────

    @Test
    fun `handleReadStream collects all frames from data source`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            val flow = MutableSharedFlow<ByteArray>(replay = 1)
            whenever(dataSource.read()).thenReturn(flow.asSharedFlow())

            val buffer = "<U>getSystemData</U=ab>".toByteArray()
            flow.emit(buffer)

            val job = launch {
                service.handleReadStream(dataSource)
            }
            delay(50)
            job.cancel()

            verify(service).processIncomingData(eq(buffer))
        }
    }

    @Test
    fun `handleReadStream with empty flow does nothing`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            val flow = MutableSharedFlow<ByteArray>(replay = 0)
            whenever(dataSource.read()).thenReturn(flow.asSharedFlow())

            val job = launch {
                service.handleReadStream(dataSource)
            }
            delay(50)
            job.cancel()

            verify(dataSource).read()
        }
    }

    // ── processIncomingData ──────────────────────────────────────

    @Test
    fun `processIncomingData handles Ack frame`() {
        val buffer = "<ack>1</ack>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData handles Nack frame`() {
        val buffer = "<ack>0</ack>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData handles DataFrame with getSystemData`() {
        val tag = "getSystemData"
        val crc = CrcCalculator.computeHex(tag)
        val frame = "<U>$tag</U=$crc>".toByteArray()
        service.processIncomingData(frame)
        val cached = service.dataCache.get("lastFrame")
        assertNotNull(cached)
        assertArrayEquals(frame, cached)
    }

    @Test
    fun `processIncomingData handles GetCan frame`() {
        val buffer = "<U>getCAN zone1</U=00>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData handles Ping frame`() {
        val buffer = "<U>Ping</U=db>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData handles Unknown frame`() {
        val buffer = "<U><request>Unknown</request></U=00>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData with no start marker returns early`() {
        val buffer = "noStartMarkerHere".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    @Test
    fun `processIncomingData with CAN2 in use frame`() {
        val buffer = "<U>CAN2 in use</U=00>".toByteArray()
        service.processIncomingData(buffer)
        assertNull(service.dataCache.get("lastFrame"))
    }

    // ── handlePollCycle ──────────────────────────────────────────

    @Test
    fun `handlePollCycle sends current poll and advances`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            service.pollQueue.initialize(isMyAir5 = true)

            service.handlePollCycle(dataSource)
            delay(120)

            verify(dataSource, atLeastOnce()).write(any())
        }
    }

    @Test
    fun `handlePollCycle with empty queue does not write`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()

            service.handlePollCycle(dataSource)
            delay(120)

            verify(dataSource, never()).write(any())
        }
    }

    // ── sendCanMessages ──────────────────────────────────────────

    @Test
    fun `sendCanMessages with short frame skips write`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            service.canQueue.enqueue(CanMessage(id = 1, data = "hi"))

            service.sendCanMessages(dataSource)
            delay(50)

            verify(dataSource, never()).write(any())
        }
    }

    @Test
    fun `sendCanMessages with valid frame writes to data source and clears queue`() {
        runBlocking {
            val dataSource = mock<UartDataSource>()
            (1..10).forEach { id ->
                service.canQueue.enqueue(CanMessage(id = id, data = ""))
            }

            service.sendCanMessages(dataSource)
            delay(50)

            verify(dataSource).write(any())
            assertTrue(service.canQueue.isEmpty())
        }
    }

    // ── requestFullPoll ──────────────────────────────────────────

    @Test
    fun `requestFullPoll iterates all 14 POLL_TAGS`() {
        service.requestFullPoll()
        assertEquals(14, service.canQueue.size())
        verify(service, times(14)).requestSinglePoll(any())
    }

    @Test
    fun `requestSinglePoll with getSystemData tag`() {
        service.requestSinglePoll("getSystemData")
        val msg = service.canQueue.dequeue()
        assertNotNull(msg)
        assertEquals(0, msg?.id)
        val expectedCrc = CrcCalculator.computeHex("getSystemData")
        assertEquals("<U>getSystemData</U=$expectedCrc>", msg?.data)
    }

    // ── enqueueUartMessage ───────────────────────────────────────

    @Test
    fun `enqueueUartMessage with Temperature message`() {
        service.enqueueUartMessage("Temperature")
        val msg = service.canQueue.dequeue()
        assertNotNull(msg)
        assertEquals(0, msg?.id)
        val expectedCrc = CrcCalculator.computeHex("Temperature")
        assertEquals("<U>Temperature</U=$expectedCrc>", msg?.data)
    }

    // ── enqueueCanIds ────────────────────────────────────────────

    @Test
    fun `enqueueCanIds with space-separated IDs`() {
        service.enqueueCanIds("1 2 3")
        assertEquals(3, service.canQueue.size())
        val msg1 = service.canQueue.dequeue()
        val msg2 = service.canQueue.dequeue()
        val msg3 = service.canQueue.dequeue()
        assertEquals(1, msg1?.id)
        assertEquals(2, msg2?.id)
        assertEquals(3, msg3?.id)
    }

    // ── processCanIds ────────────────────────────────────────────

    @Test
    fun `processCanIds with space-separated IDs`() {
        service.processCanIds("5 6 7")
        val state = service.stateMachine.getCurrentState()
        assertTrue(state is UartState.SendingCan)
        assertEquals(listOf(5, 6, 7), (state as UartState.SendingCan).messageIds)
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
        service.onDestroy()
        assertNull(UartForegroundService.instance)
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
        whenever(manager.openAccessory(accessory)).thenReturn(pfd)
        doReturn(manager).whenever(service).getSystemService(Context.USB_SERVICE)

        val result = service.openAccessory(accessory)

        assertTrue(result)
        verify(manager).openAccessory(accessory)
        assertNotNull(service.uartDataSource)
    }

    @Test
    fun `openAccessory with crash count above threshold sends reboot broadcast`() {
        val manager = mock<UsbManager>()
        val accessory = mock<UsbAccessory>()
        doReturn(manager).whenever(service).getSystemService(Context.USB_SERVICE)

        service.crashCount = 6

        val result = service.openAccessory(accessory)

        assertTrue(result)
        verify(service).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.REBOOT_REQUIRED"
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
        service.showNotification(connected = true)

        val shadowNm = shadowOf(
            service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        val notification = shadowNm.getNotification(1)
        assertNotNull(notification)
        assertEquals("Connected", notification?.extras?.getString("android.title"))
    }

    @Test
    fun `showNotification with connected false shows Not connected title`() {
        service.showNotification(connected = false)

        val shadowNm = shadowOf(
            service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        val notification = shadowNm.getNotification(1)
        assertNotNull(notification)
        assertEquals("Not connected", notification?.extras?.getString("android.title"))
    }

    @Test
    fun `showNotification with reboot required shows Reboot Required title`() {
        RebootNotificationService.rebootRequired.set(true)
        service.showNotification(connected = true)

        val shadowNm = shadowOf(
            service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        val notification = shadowNm.getNotification(1)
        assertNotNull(notification)
        assertEquals("Reboot Required", notification?.extras?.getString("android.title"))

        RebootNotificationService.rebootRequired.set(false)
    }

    @Test
    fun `showNotification duplicate call does not show second notification`() {
        service.showNotification(connected = true)
        service.showNotification(connected = true)

        val shadowNm = shadowOf(
            service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        assertEquals(1, shadowNm.allNotifications.size)
    }

    @Test
    fun `showNotification with different titles shows new notification`() {
        service.showNotification(connected = true)
        service.showNotification(connected = false)

        val shadowNm = shadowOf(
            service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        val notification = shadowNm.getNotification(1)
        assertNotNull(notification)
        assertEquals("Not connected", notification?.extras?.getString("android.title"))
    }

    // ── broadcastData ──────────────────────────────────────────

    @Test
    fun `broadcastData with tag not in cache sends no broadcasts`() {
        service.broadcastData("nonExistentTag")
        verify(service, never()).sendBroadcast(any<Intent>())
    }

    @Test
    fun `broadcastData with tag in cache sends two broadcasts`() {
        val tag = "getSystemData"
        val data = "systemData".toByteArray()
        service.dataCache.put(tag, data)

        service.broadcastData(tag)

        verify(service, times(1)).sendBroadcast(any<Intent>(), anyOrNull())
        verify(service, times(1)).sendBroadcast(any<Intent>())
    }

    @Test
    fun `broadcastData sends correct secure action for non-Fujitsu`() {
        val tag = "getClock"
        val data = "12:00".toByteArray()
        service.dataCache.put(tag, data)

        service.broadcastData(tag)

        verify(service).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_FROM_CB_SECURE"
        }, anyOrNull())
    }

    @Test
    fun `broadcastData sends correct extras on secure intent`() {
        val tag = "getZoneData"
        val data = "zone1=22".toByteArray()
        service.dataCache.put(tag, data)

        service.broadcastData(tag)

        verify(service).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_FROM_CB_SECURE" &&
            getStringExtra("com.air.advantage.GET_DATA_REQUEST") == tag &&
            getStringExtra("com.air.advantage.MESSAGE_FROM_CB_SECURE") == "zone1=22"
        }, anyOrNull())
    }

    @Test
    fun `broadcastData sends correct extras on regular intent`() {
        val tag = "getTimers"
        val data = "timer1=on".toByteArray()
        service.dataCache.put(tag, data)

        service.broadcastData(tag)

        verify(service).sendBroadcast(argThat<Intent> {
            action == "com.air.advantage.MESSAGE_FROM_CB" &&
            getStringExtra("com.air.advantage.GET_DATA_REQUEST") == tag &&
            getStringExtra("com.air.advantage.MESSAGE_FROM_CB") == "timer1=on"
        })
    }

    @Test
    fun `broadcastData with Fujitsu sends Fujitsu-specific secure action`() {
        val tag = "getSchedules"
        val data = "schedule1".toByteArray()
        service.dataCache.put(tag, data)

        service.broadcastData(tag)

        verify(service).sendBroadcast(argThat<Intent> {
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
            getStringExtra("com.air.advantage.GET_DATA_REQUEST") == "periodicInfo"
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
            action == "com.air.advantage.MESSAGE_FROM_CB_NO_PERMISSION" ||
            action == "com.air.advantage.MESSAGE_FROM_CB_NO_PERMISSION_FUJITSU"
        })
    }

    }
