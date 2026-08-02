package com.air.advantage.aaservice.ui.main

import androidx.lifecycle.ViewModel
import com.air.advantage.aaservice.service.ModeSwitchStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Transport/connection lifecycle for A1 UI; fed by service [ModeSwitchStatus]. */
enum class TransportConnectionStatus {
    Idle,
    Connecting,
    Connected,
    Error,
}

fun ModeSwitchStatus.toTransportConnectionStatus(): TransportConnectionStatus = when (this) {
    ModeSwitchStatus.Idle -> TransportConnectionStatus.Idle
    ModeSwitchStatus.Connecting -> TransportConnectionStatus.Connecting
    ModeSwitchStatus.Connected -> TransportConnectionStatus.Connected
    ModeSwitchStatus.Error -> TransportConnectionStatus.Error
}

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    private val _connectionState = MutableStateFlow<Boolean?>(null)
    val connectionState: StateFlow<Boolean?> = _connectionState.asStateFlow()

    private val _transportConnectionStatus =
        MutableStateFlow(TransportConnectionStatus.Idle)
    val transportConnectionStatus: StateFlow<TransportConnectionStatus> =
        _transportConnectionStatus.asStateFlow()

    fun setConnectionState(connected: Boolean) {
        _connectionState.value = connected
    }

    /** Updates from [com.air.advantage.aaservice.service.TransportStatusStore] / tests. */
    fun setTransportConnectionStatus(status: TransportConnectionStatus) {
        _transportConnectionStatus.value = status
    }
}
