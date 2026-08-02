package com.air.advantage.aaservice.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide transport connection status for A1 UI.
 *
 * [UartForegroundService] / [ModeSwitchCoordinator] publish updates;
 * [com.air.advantage.aaservice.ui.main.MainActivity] observes and maps to
 * [com.air.advantage.aaservice.ui.main.TransportConnectionStatus].
 *
 * Kept in the service package so the UI can depend on status without the
 * service importing ViewModels.
 */
object TransportStatusStore {
    private val _status = MutableStateFlow(ModeSwitchStatus.Idle)
    val status: StateFlow<ModeSwitchStatus> = _status.asStateFlow()

    fun publish(status: ModeSwitchStatus) {
        _status.value = status
    }

    /** Test / destroy reset. */
    fun reset() {
        _status.value = ModeSwitchStatus.Idle
    }
}
