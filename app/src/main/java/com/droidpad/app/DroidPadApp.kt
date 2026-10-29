package com.droidpad.app

import android.app.Application
import com.droidpad.app.data.SettingsRepository
import com.droidpad.app.hid.HidConnectionManager
import com.droidpad.app.hid.HidReportSender
import com.droidpad.app.hid.HidStatus
import com.droidpad.app.input.InputController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Process-wide singletons (manual DI). The HID registration outlives configuration changes. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(app)

    val connection = HidConnectionManager(
        context = app,
        lastHost = { settings.settings.value.lastHostAddress },
        autoReconnect = { settings.settings.value.autoReconnect },
        onHostConnected = { host ->
            settings.update { it.copy(lastHostAddress = host.address, lastHostName = host.name) }
        },
        qosPreset = { settings.settings.value.labQosPreset },
    )
    val sender = HidReportSender(connection)
    val input = InputController(sender)

    init {
        connection.onLedsChanged = input::onHostLeds
        settings.settings
            .map { it.autoLanguageSwitch to it.languageToggleKey }
            .distinctUntilChanged()
            .onEach { (auto, key) -> input.configure(auto, key) }
            .launchIn(appScope)
        settings.settings
            .map { it.labMoveIntervalMs }
            .distinctUntilChanged()
            .onEach { sender.moveIntervalMs = it.toLong() }
            .launchIn(appScope)
        settings.settings
            .map { it.labQosPreset }
            .distinctUntilChanged()
            .drop(1) // the first value is used by the initial registration
            .onEach { connection.reregister() }
            .launchIn(appScope)
        connection.state
            .map { state -> state.host?.address.takeIf { state.status == HidStatus.CONNECTED } }
            .distinctUntilChanged()
            .onEach { input.onHostReset() }
            .launchIn(appScope)
    }
}

class DroidPadApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
