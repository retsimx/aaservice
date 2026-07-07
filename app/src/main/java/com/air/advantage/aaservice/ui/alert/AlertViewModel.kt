package com.air.advantage.aaservice.ui.alert

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AlertViewModel @Inject constructor() : ViewModel() {

    private val _alertActive = MutableStateFlow(false)
    val alertActive: StateFlow<Boolean> = _alertActive.asStateFlow()

    fun dismiss() {
        _alertActive.value = false
    }
}