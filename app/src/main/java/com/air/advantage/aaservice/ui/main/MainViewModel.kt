package com.air.advantage.aaservice.ui.main

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    private val _connectionState = MutableStateFlow<Boolean?>(null)
    val connectionState: StateFlow<Boolean?> = _connectionState.asStateFlow()

    fun setConnectionState(connected: Boolean) {
        _connectionState.value = connected
    }
}