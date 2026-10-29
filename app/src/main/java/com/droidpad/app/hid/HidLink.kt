package com.droidpad.app.hid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The connection the rest of the app talks to: routes reports and commands to the classic
 * [HidConnectionManager] or, when Labs › connection type is BLE, to [BleHidPeripheral]. Only one
 * transport runs at a time, so the host never sees DroidPad twice.
 */
class HidLink(
    val classic: HidConnectionManager,
    val ble: BleHidPeripheral,
    scope: CoroutineScope,
    initial: HidTransport,
) : ReportTransport {

    private val mode = MutableStateFlow(initial)
    @Volatile private var active: ReportTransport = if (initial == HidTransport.BLE) ble else classic
    private var started = false

    val transport: HidTransport get() = mode.value

    val state: StateFlow<HidState> = combine(mode, classic.state, ble.state, ::merge)
        .stateIn(scope, SharingStarted.Eagerly, merge(initial, classic.state.value, ble.state.value))

    var onLedsChanged: ((Int) -> Unit)? = null
        set(value) {
            field = value
            classic.onLedsChanged = value
            ble.onLedsChanged = value
        }

    val adapterName: String? get() = classic.adapterName

    fun hasPermissions() = classic.hasPermissions()

    /** Switches transports; the old one disconnects and releases Bluetooth first. */
    fun setTransport(t: HidTransport) {
        if (t == mode.value) return
        if (started) {
            if (t == HidTransport.BLE) classic.shutdown() else ble.stop()
        }
        active = if (t == HidTransport.BLE) ble else classic
        mode.value = t
        if (started) start()
    }

    fun start() {
        started = true
        if (mode.value == HidTransport.BLE) {
            classic.refreshBonded()
            ble.start()
        } else {
            classic.start()
        }
    }

    fun shutdown() {
        started = false
        if (mode.value == HidTransport.BLE) ble.stop() else classic.shutdown()
    }

    override fun send(reportId: Int, data: ByteArray): Boolean = active.send(reportId, data)

    /** In BLE mode the host connects to DroidPad, so "connect" just makes sure it is advertising. */
    fun connect(address: String): Boolean =
        if (mode.value == HidTransport.BLE) true.also { ble.start() } else classic.connect(address)

    fun disconnect() = if (mode.value == HidTransport.BLE) ble.disconnect() else classic.disconnect()

    fun cancelReconnect() = classic.cancelReconnect()
    fun pair(address: String) = classic.pair(address)
    fun startDiscovery() = classic.startDiscovery()
    fun stopDiscovery() = classic.stopDiscovery()
    fun onDiscoverable(seconds: Int) = classic.onDiscoverable(seconds)

    /** Re-registers the classic HID app (QoS changes); BLE has no equivalent. */
    fun reregister() {
        if (mode.value == HidTransport.CLASSIC) classic.reregister()
    }

    companion object {
        /** The UI state for [mode]: pairing/discovery come from classic, the link from the active transport. */
        fun merge(mode: HidTransport, classic: HidState, ble: HidState): HidState =
            if (mode == HidTransport.CLASSIC) {
                classic.copy(transport = HidTransport.CLASSIC)
            } else {
                classic.copy(
                    status = ble.status,
                    host = ble.host,
                    leds = ble.leds,
                    error = ble.error,
                    reconnectAttempt = 0,
                    transport = HidTransport.BLE,
                )
            }
    }
}
