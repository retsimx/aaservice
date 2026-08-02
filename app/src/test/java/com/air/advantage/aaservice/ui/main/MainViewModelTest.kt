package com.air.advantage.aaservice.ui.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MainViewModelTest {

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        viewModel = MainViewModel()
    }

    @Test
    fun connectionState_is_initially_null() = runBlocking {
        assertEquals(null, viewModel.connectionState.first())
    }

    @Test
    fun setConnectionState_true_updates_flow() = runBlocking {
        viewModel.setConnectionState(true)
        assertEquals(true, viewModel.connectionState.first())
    }

    @Test
    fun setConnectionState_false_updates_flow() = runBlocking {
        viewModel.setConnectionState(false)
        assertEquals(false, viewModel.connectionState.first())
    }

    @Test
    fun multiple_setConnectionState_calls_update_correctly() = runBlocking {
        viewModel.setConnectionState(true)
        assertEquals(true, viewModel.connectionState.first())

        viewModel.setConnectionState(false)
        assertEquals(false, viewModel.connectionState.first())

        viewModel.setConnectionState(true)
        assertEquals(true, viewModel.connectionState.first())
    }

    @Test
    fun transportConnectionStatus_defaults_to_idle() = runBlocking {
        assertEquals(TransportConnectionStatus.Idle, viewModel.transportConnectionStatus.first())
    }

    @Test
    fun setTransportConnectionStatus_updates_flow() = runBlocking {
        viewModel.setTransportConnectionStatus(TransportConnectionStatus.Connecting)
        assertEquals(
            TransportConnectionStatus.Connecting,
            viewModel.transportConnectionStatus.first(),
        )

        viewModel.setTransportConnectionStatus(TransportConnectionStatus.Connected)
        assertEquals(
            TransportConnectionStatus.Connected,
            viewModel.transportConnectionStatus.first(),
        )

        viewModel.setTransportConnectionStatus(TransportConnectionStatus.Error)
        assertEquals(
            TransportConnectionStatus.Error,
            viewModel.transportConnectionStatus.first(),
        )

        viewModel.setTransportConnectionStatus(TransportConnectionStatus.Idle)
        assertEquals(
            TransportConnectionStatus.Idle,
            viewModel.transportConnectionStatus.first(),
        )
    }

    @Test
    fun setTransportConnectionStatus_does_not_affect_connectionState() = runBlocking {
        viewModel.setConnectionState(true)
        viewModel.setTransportConnectionStatus(TransportConnectionStatus.Error)

        assertEquals(true, viewModel.connectionState.first())
        assertEquals(TransportConnectionStatus.Error, viewModel.transportConnectionStatus.first())
    }
}
