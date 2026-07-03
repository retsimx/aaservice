package com.air.advantage.aaservice.domain.state

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UartStateMachineTest {
    
    private lateinit var stateMachine: UartStateMachine
    
    @Before
    fun setUp() {
        stateMachine = UartStateMachine()
    }
    
    @Test
    fun `initial state is Disconnected`() {
        assertEquals(UartState.Disconnected, stateMachine.getCurrentState())
    }
    
    @Test
    fun `Connecting to ConfigSent transition`() {
        stateMachine.onConfigSent()
        assertEquals(UartState.ConfigSent, stateMachine.getCurrentState())
    }
    
    @Test
    fun `ConfigSent to Polling(0) transition`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        assertEquals(UartState.Polling(0), stateMachine.getCurrentState())
        assertEquals(0, stateMachine.getPollIndex())
    }
    
    @Test
    fun `Polling to AwaitingResponse transition`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        val transition = stateMachine.onSendPoll("getSystemData", byteArrayOf(1, 2, 3))
        assertTrue(transition is StateTransition.SendData)
        assertEquals(UartState.AwaitingResponse("getSystemData", 0, true), stateMachine.getCurrentState())
    }
    
    @Test
    fun `AwaitingResponse to Polling on valid response`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        stateMachine.onSendPoll("getSystemData", byteArrayOf(1, 2, 3))
        stateMachine.onValidResponse("getSystemData")
        assertEquals(UartState.Polling(1), stateMachine.getCurrentState())
        assertEquals(1, stateMachine.getPollIndex())
    }
    
    @Test
    fun `retry logic - first retry`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        stateMachine.onSendPoll("getSystemData", byteArrayOf(1, 2, 3))
        stateMachine.onNoResponse()
        assertEquals(UartState.AwaitingResponse("getSystemData", 1, true), stateMachine.getCurrentState())
    }
    
    @Test
    fun `retry logic - second retry`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        stateMachine.onSendPoll("getSystemData", byteArrayOf(1, 2, 3))
        stateMachine.onNoResponse()
        stateMachine.onNoResponse()
        assertEquals(UartState.AwaitingResponse("getSystemData", 2, true), stateMachine.getCurrentState())
    }
    
    @Test
    fun `retry logic - third retry skips`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        stateMachine.onSendPoll("getSystemData", byteArrayOf(1, 2, 3))
        stateMachine.onNoResponse()
        stateMachine.onNoResponse()
        stateMachine.onNoResponse()
        assertEquals(UartState.Polling(1), stateMachine.getCurrentState())
    }
    
    @Test
    fun `CAN queued transitions to SendingCan`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        val ids = listOf(1, 2, 3)
        stateMachine.onCanQueued(ids)
        assertEquals(UartState.SendingCan(ids), stateMachine.getCurrentState())
    }
    
    @Test
    fun `CAN ack resumes polling`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        stateMachine.onCanQueued(listOf(1, 2, 3))
        stateMachine.onCanAck()
        assertEquals(UartState.Polling(0), stateMachine.getCurrentState())
    }
    
    @Test
    fun `CAN in use transitions to CanBusy`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        stateMachine.onSendPoll("getSystemData", byteArrayOf(1, 2, 3))
        stateMachine.onCanInUse()
        assertEquals(UartState.CanBusy, stateMachine.getCurrentState())
        assertTrue(stateMachine.isCanBusy())
    }
    
    @Test
    fun `error from any state`() {
        stateMachine.onConfigSent()
        val transition = stateMachine.onError("test error")
        assertTrue(transition is StateTransition.CloseConnection)
        assertEquals(UartState.Error("test error"), stateMachine.getCurrentState())
    }
    
    @Test
    fun `reset clears all state`() {
        stateMachine.onConfigSent()
        stateMachine.onStartPoll()
        stateMachine.reset()
        assertEquals(UartState.Disconnected, stateMachine.getCurrentState())
        assertEquals(0, stateMachine.getPollIndex())
        assertFalse(stateMachine.isCanBusy())
    }
}
