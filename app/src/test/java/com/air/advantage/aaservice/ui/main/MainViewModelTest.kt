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
}