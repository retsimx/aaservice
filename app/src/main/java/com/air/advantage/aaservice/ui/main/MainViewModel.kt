package com.air.advantage.aaservice.ui.main

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Stub transport/connection lifecycle for UI (A1); wired to service/WS in A6. */
enum class TransportConnectionStatus {
    Idle,
    Connecting,
    Connected,
    Error,
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

    /** Setter for future A6/service updates; A1 UI always shows Idle. */
    fun setTransportConnectionStatus(status: TransportConnectionStatus) {
        _transportConnectionStatus.value = status
    }
}
