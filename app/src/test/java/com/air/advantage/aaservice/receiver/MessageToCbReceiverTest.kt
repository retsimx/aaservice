package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

class MessageToCbReceiverTest {

    private lateinit var receiver: MessageToCbReceiver
    private lateinit var context: Context
    private lateinit var service: UartForegroundService

    @Before
    fun setUp() {
        receiver = MessageToCbReceiver()
        context = mock(Context::class.java)
        service = mock(UartForegroundService::class.java)
        UartForegroundService.instance = service
    }

    @After
    fun tearDown() {
        UartForegroundService.instance = null
    }

    private fun intentWithMessage(message: String?): Intent {
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra("com.air.advantage.MESSAGE_TO_CB")).thenReturn(message)
        return intent
    }

    @Test
    fun `onReceive with device closed does nothing`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(false))
        whenever(service.isWsMode()).thenReturn(false)

        receiver.onReceive(context, intentWithMessage("setZoneData?zone=1"))

        verify(service, never()).enqueueUartMessage(anyString())
    }

    @Test
    fun `onReceive WS mode enqueues setAircon when device closed`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(false))
        whenever(service.isWsMode()).thenReturn(true)

        receiver.onReceive(
            context,
            intentWithMessage("""setAircon?json={"aircons":{"ac1":{"info":{"state":"on"}}}}"""),
        )

        verify(service).enqueueUartMessage(
            """setAircon?json={"aircons":{"ac1":{"info":{"state":"on"}}}}""",
        )
    }

    @Test
    fun `onReceive with message without question mark is dropped`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, intentWithMessage("Temperature"))

        verify(service, never()).enqueueUartMessage(anyString())
    }

    @Test
    fun `onReceive with command before question mark enqueues message`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, intentWithMessage("setZoneData?zone=1"))

        verify(service).enqueueUartMessage("setZoneData?zone=1")
    }

    @Test
    fun `onReceive with Light command before question mark is filtered`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, intentWithMessage("Light?x"))

        verify(service, never()).enqueueUartMessage(anyString())
    }

    @Test
    fun `onReceive with Aircon command before question mark is filtered`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, intentWithMessage("Aircon?x"))

        verify(service, never()).enqueueUartMessage(anyString())
    }

    @Test
    fun `onReceive with Activation command before question mark is filtered`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, intentWithMessage("Activation?x"))

        verify(service, never()).enqueueUartMessage(anyString())
    }

    @Test
    fun `onReceive with MySystem command before question mark is filtered`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, intentWithMessage("MySystem?x"))

        verify(service, never()).enqueueUartMessage(anyString())
    }

    @Test
    fun `onReceive with keyword only after question mark is not filtered`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, intentWithMessage("setSystemData?Light=on"))

        verify(service).enqueueUartMessage("setSystemData?Light=on")
    }

    @Test
    fun `onReceive with null extra is dropped`() {
        whenever(service.deviceOpen).thenReturn(AtomicBoolean(true))

        receiver.onReceive(context, intentWithMessage(null))

        verify(service, never()).enqueueUartMessage(anyString())
    }

    @Test
    fun `onReceive with null service does nothing`() {
        UartForegroundService.instance = null

        receiver.onReceive(context, intentWithMessage("setZoneData?zone=1"))

        verifyNoInteractions(service)
    }
}
