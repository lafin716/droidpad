package com.droidpad.app.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

enum class HidStatus {
    INITIALIZING,
    NO_BLUETOOTH,
    PERMISSION_REQUIRED,
    BLUETOOTH_OFF,
    UNSUPPORTED,
    REGISTERING,
    READY,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
}

data class HostDevice(val address: String, val name: String, val bonded: Boolean)

data class HidState(
    val status: HidStatus = HidStatus.INITIALIZING,
    val host: HostDevice? = null,
    val bondedDevices: List<HostDevice> = emptyList(),
    val discoveredDevices: List<HostDevice> = emptyList(),
    val discovering: Boolean = false,
    val reconnectAttempt: Int = 0,
    val leds: Int = 0,
    val error: String? = null,
    /** [android.os.SystemClock.elapsedRealtime] at which "visible to other devices" ends; null when not visible. */
    val discoverableUntil: Long? = null,
)

/**
 * Owns the [BluetoothHidDevice] profile proxy: registers DroidPad as a keyboard + mouse combo,
 * tracks connection state and reconnects to the last host. All callbacks are funnelled to the
 * main thread; state is published through [state].
 */
@SuppressLint("MissingPermission") // every entry point checks hasConnectPermission() first
class HidConnectionManager(
    private val context: Context,
    private val lastHost: () -> String?,
    private val autoReconnect: () -> Boolean,
    private val onHostConnected: (HostDevice) -> Unit,
) : ReportTransport {

    private val main = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hid-callback").apply { isDaemon = true }
    }
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(HidState())
    val state: StateFlow<HidState> = _state.asStateFlow()

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var connected: BluetoothDevice? = null
    private var registered = false
    private var proxyRequested = false
    private var receiversRegistered = false
    private var userDisconnected = false
    private val reconnect = ReconnectPolicy()
    private val reconnectRunnable = Runnable { attemptReconnect() }
    private val restartRunnable = Runnable { start() }
    /** Set by [shutdown] so late profile callbacks don't re-register; cleared by [start]. */
    private var closed = false
    /** Device the user asked to pair from this app; connected automatically once bonded. */
    private var pairingAddress: String? = null
    private val proxyTimeout = Runnable {
        if (hid == null) setStatus(HidStatus.UNSUPPORTED, "HID Device profile proxy did not respond")
    }

    /** Invoked with the host's keyboard LED bitmask (Num/Caps/Scroll). */
    var onLedsChanged: ((Int) -> Unit)? = null

    /** Every permission the app asks for (connect + discovery + discoverability). */
    fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all(::granted)

    /** Enough to register the HID app and talk to bonded hosts. */
    private fun hasConnectPermission(): Boolean = granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasScanPermission(): Boolean =
        hasConnectPermission() && granted(Manifest.permission.BLUETOOTH_SCAN)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** (Re)evaluates the environment and moves towards a registered HID app. Safe to call often. */
    fun start() {
        closed = false
        val a = adapter ?: return setStatus(HidStatus.NO_BLUETOOTH)
        if (!hasConnectPermission()) return setStatus(HidStatus.PERMISSION_REQUIRED)
        registerReceivers()
        if (!a.isEnabled) return setStatus(HidStatus.BLUETOOTH_OFF)
        refreshBonded()
        val proxy = hid
        when {
            proxy != null && registered -> Unit
            proxy != null -> register(proxy)
            !proxyRequested -> requestProxy(a)
        }
    }

    private fun requestProxy(a: BluetoothAdapter) {
        proxyRequested = true
        setStatus(HidStatus.REGISTERING)
        val ok = a.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        if (!ok) {
            proxyRequested = false
            setStatus(HidStatus.UNSUPPORTED, "This device does not expose the Bluetooth HID Device profile")
        } else {
            main.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            main.removeCallbacks(proxyTimeout)
            val device = proxy as BluetoothHidDevice
            // A stale request (e.g. from before shutdown()) may deliver a second proxy.
            if (closed || (hid != null && hid !== device)) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
                proxyRequested = false
                return
            }
            hid = device
            register(device)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            connected = null
            registered = false
            proxyRequested = false
            _state.update { it.copy(host = null) }
            if (!closed && adapter?.isEnabled == true) {
                setStatus(HidStatus.REGISTERING)
                main.removeCallbacks(restartRunnable)
                main.postDelayed(restartRunnable, 1_000)
            }
        }
    }

    private fun register(device: BluetoothHidDevice) {
        if (!hasConnectPermission()) return setStatus(HidStatus.PERMISSION_REQUIRED)
        setStatus(HidStatus.REGISTERING)
        val sdp = BluetoothHidDeviceAppSdpSettings(
            SDP_NAME, SDP_DESCRIPTION, SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.bytes,
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX,
        )
        val ok = try {
            device.registerApp(sdp, null, qos, callbackExecutor, hidCallback)
        } catch (e: SecurityException) {
            false.also { Log.w(TAG, "registerApp denied", e) }
        }
        if (!ok) setStatus(HidStatus.ERROR, "Could not register as a HID device. Another HID app may be active.")
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) = main.post {
            this@HidConnectionManager.registered = registered
            if (!registered) {
                connected = null
                _state.update { it.copy(host = null) }
                if (hid != null && adapter?.isEnabled == true) setStatus(HidStatus.ERROR, "HID app was unregistered")
                return@post
            }
            if (connected == null) setStatus(HidStatus.READY)
            val target = pluggedDevice ?: lastHost()?.let(::remoteDevice)
            if (target != null && autoReconnect() && connected == null) {
                reconnect.reset()
                connectTo(target)
            }
        }.let { }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) = main.post {
            onConnectionState(device, state)
        }.let { }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val proxy = hid ?: return
            val size = if (id.toInt() == HidDescriptor.REPORT_ID_MOUSE) {
                HidDescriptor.MOUSE_INPUT_SIZE
            } else {
                HidDescriptor.KEYBOARD_INPUT_SIZE
            }
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                proxy.replyReport(device, type, id, ByteArray(size))
            } else {
                proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) handleLeds(id.toInt(), data)
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            handleLeds(reportId.toInt(), data)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) = main.post {
            connected = null
            _state.update { it.copy(host = null) }
            setStatus(HidStatus.READY)
        }.let { }
    }

    private fun handleLeds(reportId: Int, data: ByteArray) {
        val leds = when {
            data.isEmpty() -> return
            // Some stacks pass the report id as the first byte, others strip it.
            data.size >= 2 && data[0].toInt() == HidDescriptor.REPORT_ID_KEYBOARD -> data[1].toInt()
            reportId == HidDescriptor.REPORT_ID_KEYBOARD || reportId == 0 -> data[0].toInt()
            else -> return
        } and 0x07
        main.post {
            _state.update { it.copy(leds = leds) }
            onLedsChanged?.invoke(leds)
        }
    }

    private fun onConnectionState(device: BluetoothDevice, state: Int) {
        val host = device.toHost()
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                connected = device
                reconnect.reset()
                userDisconnected = false
                main.removeCallbacks(reconnectRunnable)
                _state.update { it.copy(host = host, reconnectAttempt = 0, error = null) }
                setStatus(HidStatus.CONNECTED)
                onHostConnected(host)
            }
            BluetoothProfile.STATE_CONNECTING -> {
                _state.update { it.copy(host = host) }
                setStatus(HidStatus.CONNECTING)
            }
            BluetoothProfile.STATE_DISCONNECTING -> setStatus(HidStatus.DISCONNECTING)
            BluetoothProfile.STATE_DISCONNECTED -> {
                val wasConnected = connected?.address == device.address
                if (wasConnected || connected == null) {
                    connected = null
                    _state.update { it.copy(host = null) }
                    if (registered) setStatus(HidStatus.READY)
                    if (!userDisconnected && autoReconnect() && device.address == lastHost()) scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        val delay = reconnect.nextDelayMs() ?: run {
            _state.update { it.copy(reconnectAttempt = 0) }
            return
        }
        _state.update { it.copy(reconnectAttempt = reconnect.attempt) }
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, delay)
    }

    private fun attemptReconnect() {
        if (connected != null || !registered) return
        val target = lastHost()?.let(::remoteDevice) ?: return
        if (!connectTo(target)) scheduleReconnect()
    }

    /** Connects to [address]; the host must already be paired with this phone. */
    fun connect(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        userDisconnected = false
        reconnect.reset()
        main.removeCallbacks(reconnectRunnable)
        return connectTo(device)
    }

    private fun connectTo(device: BluetoothDevice): Boolean {
        val proxy = hid
        if (proxy == null || !registered || !hasConnectPermission()) return false
        stopDiscovery()
        val current = connected
        if (current != null && current.address != device.address) proxy.disconnect(current)
        _state.update { it.copy(host = device.toHost(), error = null) }
        setStatus(HidStatus.CONNECTING)
        val ok = proxy.connect(device)
        if (!ok) {
            setStatus(HidStatus.READY, "Connection request was rejected")
            _state.update { it.copy(host = null) }
        }
        return ok
    }

    fun disconnect() {
        userDisconnected = true
        main.removeCallbacks(reconnectRunnable)
        reconnect.reset()
        _state.update { it.copy(reconnectAttempt = 0) }
        val device = connected ?: return
        if (!hasConnectPermission()) return
        setStatus(HidStatus.DISCONNECTING)
        if (hid?.disconnect(device) != true) {
            connected = null
            _state.update { it.copy(host = null) }
            setStatus(HidStatus.READY)
        }
    }

    fun cancelReconnect() {
        main.removeCallbacks(reconnectRunnable)
        reconnect.reset()
        _state.update { it.copy(reconnectAttempt = 0) }
    }

    override fun send(reportId: Int, data: ByteArray): Boolean {
        val device = connected ?: return false
        val proxy = hid ?: return false
        return try {
            proxy.sendReport(device, reportId, data)
        } catch (e: SecurityException) {
            false
        }
    }

    fun startDiscovery(): Boolean {
        val a = adapter ?: return false
        if (!hasScanPermission() || !a.isEnabled) return false
        _state.update { it.copy(discoveredDevices = emptyList()) }
        if (a.isDiscovering) a.cancelDiscovery()
        return a.startDiscovery()
    }

    fun stopDiscovery() {
        val a = adapter ?: return
        if (hasScanPermission() && a.isDiscovering) a.cancelDiscovery()
    }

    /** Starts pairing; the host shows a confirmation dialog. */
    fun pair(address: String): Boolean {
        val device = remoteDevice(address) ?: return false
        stopDiscovery()
        pairingAddress = address
        return device.createBond()
    }

    fun refreshBonded() {
        val a = adapter ?: return
        if (!hasConnectPermission()) return
        val bonded = try {
            a.bondedDevices.orEmpty().map { it.toHost() }.sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
        _state.update { s ->
            s.copy(
                bondedDevices = bonded,
                discoveredDevices = s.discoveredDevices.filter { d -> bonded.none { it.address == d.address } },
            )
        }
    }

    val adapterName: String?
        get() = if (hasConnectPermission()) adapter?.name else null

    private fun remoteDevice(address: String): BluetoothDevice? {
        val a = adapter ?: return null
        if (!BluetoothAdapter.checkBluetoothAddress(address) || !hasConnectPermission()) return null
        return a.getRemoteDevice(address)
    }

    private fun BluetoothDevice.toHost() = HostDevice(
        address = address,
        name = name?.takeIf { it.isNotBlank() } ?: address,
        bonded = bondState == BluetoothDevice.BOND_BONDED,
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                ) {
                    BluetoothAdapter.STATE_ON -> start()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    refreshBonded()
                    val device = intent.parcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    // Only the host the user chose to pair with (or the last host); never
                    // auto-connect to some other device that happened to bond meanwhile.
                    if (device != null && bond == BluetoothDevice.BOND_BONDED && connected == null &&
                        (device.address == pairingAddress || device.address == lastHost())
                    ) {
                        pairingAddress = null
                        connect(device.address)
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.parcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (!hasScanPermission()) return
                    val host = device.toHost()
                    if (host.bonded || device.name.isNullOrBlank()) return
                    _state.update { s ->
                        if (s.discoveredDevices.any { it.address == host.address }) s
                        else s.copy(discoveredDevices = s.discoveredDevices + host)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _state.update { it.copy(discovering = true) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _state.update { it.copy(discovering = false) }
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                    val mode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.SCAN_MODE_NONE)
                    if (mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                        if (_state.value.discoverableUntil == null) onDiscoverable(DEFAULT_DISCOVERABLE_SECONDS)
                    } else {
                        clearDiscoverable()
                    }
                }
            }
        }
    }

    /** Called when the user accepted the "make visible" request for [seconds]. */
    fun onDiscoverable(seconds: Int) {
        if (seconds <= 0) return
        main.removeCallbacks(discoverableTimeout)
        _state.update { it.copy(discoverableUntil = SystemClock.elapsedRealtime() + seconds * 1000L) }
        main.postDelayed(discoverableTimeout, seconds * 1000L)
    }

    private fun clearDiscoverable() {
        main.removeCallbacks(discoverableTimeout)
        _state.update { it.copy(discoverableUntil = null) }
    }

    private val discoverableTimeout = Runnable { _state.update { it.copy(discoverableUntil = null) } }

    private fun onBluetoothOff() {
        clearDiscoverable()
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(proxyTimeout)
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hid = null
        connected = null
        registered = false
        proxyRequested = false
        _state.update { it.copy(host = null, discovering = false, reconnectAttempt = 0) }
        setStatus(HidStatus.BLUETOOTH_OFF)
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Unregisters the HID app so other apps (or a later launch) can claim it. */
    fun shutdown() {
        closed = true
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(proxyTimeout)
        main.removeCallbacks(restartRunnable)
        main.removeCallbacks(discoverableTimeout)
        if (receiversRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiversRegistered = false
        }
        stopDiscovery()
        hid?.let { proxy ->
            if (hasConnectPermission()) {
                connected?.let { proxy.disconnect(it) }
                proxy.unregisterApp()
            }
            // Closing the proxy needs no permission; always release it.
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        }
        hid = null
        connected = null
        registered = false
        proxyRequested = false
    }

    private fun setStatus(status: HidStatus, error: String? = null) {
        _state.update { it.copy(status = status, error = error) }
    }

    companion object {
        private const val TAG = "HidConnection"
        private const val PROXY_TIMEOUT_MS = 5_000L
        const val DEFAULT_DISCOVERABLE_SECONDS = 120
        const val SDP_NAME = "DroidPad"
        const val SDP_DESCRIPTION = "Keyboard and touchpad"
        const val SDP_PROVIDER = "DroidPad"

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)
