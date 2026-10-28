package com.droidpad.app

import androidx.lifecycle.ViewModel
import com.droidpad.app.data.AppSettings
import com.droidpad.app.data.SettingsRepository
import com.droidpad.app.hid.HidConnectionManager
import com.droidpad.app.hid.HidState
import com.droidpad.app.input.InputController
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(
    private val settingsRepo: SettingsRepository,
    val connection: HidConnectionManager,
    val input: InputController,
) : ViewModel() {
    val hid: StateFlow<HidState> = connection.state
    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val modifiers = input.modifiers
    val hostLanguage = input.hostLanguage
    val capsLock = input.capsLock
    val mirrored = input.mirrored
    val unsupportedCount: StateFlow<Int> = input.unsupportedCount

    fun refresh() = connection.start()
    fun connect(address: String) = connection.connect(address)
    fun disconnect() = connection.disconnect()
    fun cancelReconnect() = connection.cancelReconnect()
    fun pair(address: String) = connection.pair(address)
    fun startDiscovery() = connection.startDiscovery()
    fun stopDiscovery() = connection.stopDiscovery()

    fun forgetHost() {
        connection.cancelReconnect()
        settingsRepo.update { it.copy(lastHostAddress = null, lastHostName = null) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsRepo.update(transform)

    override fun onCleared() {
        connection.stopDiscovery()
    }
}
