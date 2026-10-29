package com.droidpad.app.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Labs: DroidPad as a Bluetooth LE HID device (HID over GATT). Hosts the HID, Battery and Device
 * Information services on a [BluetoothGattServer], advertises the HID service and sends input
 * reports as notifications, one at a time. The host is the device that subscribes to an input
 * report; bonded hosts reconnect on their own while DroidPad advertises.
 */
@SuppressLint("MissingPermission") // start() checks the permissions; the rest only runs after it
class BleHidPeripheral(private val context: Context) : ReportTransport {

    private val main = Handler(Looper.getMainLooper())
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val prefs = context.getSharedPreferences("droidpad_ble", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(HidState(transport = HidTransport.BLE))
    val state: StateFlow<HidState> = _state.asStateFlow()

    /** Invoked with the host's keyboard LED bitmask (Num/Caps/Scroll). */
    var onLedsChanged: ((Int) -> Unit)? = null

    private var running = false
    private var receiverRegistered = false
    private var server: BluetoothGattServer? = null
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private var servicesReady = false
    private var advertising = false
    private var advertiseWithName = true
    /** Set by [disconnect] so the dropped host is not invited back until [start]. */
    private var paused = false
    private var priorityClient: BluetoothGatt? = null

    @Volatile private var host: BluetoothDevice? = null
    private var protocolMode = Hogp.PROTOCOL_MODE_REPORT.toByte()
    @Volatile private var leds = 0

    private lateinit var keyboardInput: BluetoothGattCharacteristic
    private lateinit var mouseInput: BluetoothGattCharacteristic
    private lateinit var keyboardOutput: BluetoothGattCharacteristic
    private lateinit var reportMap: BluetoothGattCharacteristic
    private lateinit var hidInformation: BluetoothGattCharacteristic
    private lateinit var controlPoint: BluetoothGattCharacteristic
    private lateinit var protocolModeChar: BluetoothGattCharacteristic
    private lateinit var batteryLevel: BluetoothGattCharacteristic
    private lateinit var pnpId: BluetoothGattCharacteristic
    private lateinit var manufacturer: BluetoothGattCharacteristic
    private val reportReferences = IdentityHashMap<BluetoothGattDescriptor, ByteArray>()

    /** Input report ids each connected device has enabled notifications for, by address. */
    private val subscriptions = ConcurrentHashMap<String, Set<Int>>()
    /** Bonded hosts that subscribed before; they may skip re-writing the CCCD on reconnect. */
    private val knownHosts: MutableSet<String> =
        prefs.getStringSet(K_HOSTS, emptySet()).orEmpty().toMutableSet()

    private val notifyLock = Object()
    private var notifyInFlight = false

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Opens the GATT server and advertises until a host subscribes. Safe to call often. */
    fun start() {
        running = true
        paused = false
        val a = adapter ?: return setStatus(HidStatus.NO_BLUETOOTH)
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT) || !granted(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            return setStatus(HidStatus.PERMISSION_REQUIRED)
        }
        registerReceiver()
        if (!a.isEnabled) return setStatus(HidStatus.BLUETOOTH_OFF)
        when {
            server == null -> openServer()
            servicesReady && host == null -> startAdvertising()
        }
    }

    /** Closes the GATT server and stops advertising; the host sees DroidPad disconnect. */
    fun stop() {
        running = false
        teardown()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        setStatus(HidStatus.INITIALIZING)
    }

    /** Drops the host connection and stops advertising until [start] is called again. */
    fun disconnect() {
        paused = true
        stopAdvertising()
        val device = host ?: return
        setStatus(HidStatus.DISCONNECTING)
        closePriorityClient()
        server?.cancelConnection(device)
    }

    private fun openServer() {
        setStatus(HidStatus.REGISTERING)
        val s = try {
            manager?.openGattServer(context, serverCallback)
        } catch (e: SecurityException) {
            null.also { Log.w(TAG, "openGattServer denied", e) }
        } ?: return setStatus(HidStatus.UNSUPPORTED, "Could not open a Bluetooth LE GATT server")
        server = s
        servicesReady = false
        pendingServices.clear()
        pendingServices += deviceInfoService()
        pendingServices += batteryService()
        pendingServices += hidService()
        addNextService()
    }

    /** Services must be added one at a time, each after the previous onServiceAdded. */
    private fun addNextService() {
        val s = server ?: return
        val next = pendingServices.removeFirstOrNull()
        if (next == null) {
            servicesReady = true
            Log.i(TAG, "GATT services ready")
            startAdvertising()
            return
        }
        if (!s.addService(next)) setStatus(HidStatus.ERROR, "Could not add GATT service ${next.uuid}")
    }

    private fun hidService() = BluetoothGattService(Hogp.SERVICE_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        hidInformation = readOnly(Hogp.CHAR_HID_INFORMATION)
        reportMap = readOnly(Hogp.CHAR_REPORT_MAP)
        controlPoint = BluetoothGattCharacteristic(
            Hogp.CHAR_HID_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        protocolModeChar = BluetoothGattCharacteristic(
            Hogp.CHAR_PROTOCOL_MODE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        keyboardInput = inputReport(HidDescriptor.REPORT_ID_KEYBOARD)
        mouseInput = inputReport(HidDescriptor.REPORT_ID_MOUSE)
        keyboardOutput = BluetoothGattCharacteristic(
            Hogp.CHAR_REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        ).apply { addReportReference(HidDescriptor.REPORT_ID_KEYBOARD, Hogp.REPORT_TYPE_OUTPUT) }
        listOf(hidInformation, reportMap, controlPoint, protocolModeChar, keyboardInput, mouseInput, keyboardOutput)
            .forEach(::addCharacteristic)
    }

    private fun batteryService() = BluetoothGattService(Hogp.SERVICE_BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        batteryLevel = readOnly(Hogp.CHAR_BATTERY_LEVEL)
        addCharacteristic(batteryLevel)
    }

    private fun deviceInfoService() = BluetoothGattService(Hogp.SERVICE_DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        pnpId = readOnly(Hogp.CHAR_PNP_ID)
        manufacturer = readOnly(Hogp.CHAR_MANUFACTURER_NAME)
        addCharacteristic(pnpId)
        addCharacteristic(manufacturer)
    }

    private fun readOnly(uuid: java.util.UUID) = BluetoothGattCharacteristic(
        uuid, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
    )

    private fun inputReport(reportId: Int) = BluetoothGattCharacteristic(
        Hogp.CHAR_REPORT,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                Hogp.DESC_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        addReportReference(reportId, Hogp.REPORT_TYPE_INPUT)
    }

    private fun BluetoothGattCharacteristic.addReportReference(reportId: Int, type: Int) {
        val descriptor = BluetoothGattDescriptor(Hogp.DESC_REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED)
        reportReferences[descriptor] = Hogp.reportReference(reportId, type)
        addDescriptor(descriptor)
    }

    private fun inputReportId(characteristic: BluetoothGattCharacteristic?): Int? = when {
        characteristic == null -> null
        characteristic === keyboardInput -> HidDescriptor.REPORT_ID_KEYBOARD
        characteristic === mouseInput -> HidDescriptor.REPORT_ID_MOUSE
        else -> null
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setStatus(HidStatus.ERROR, "Adding GATT service ${service.uuid} failed ($status)")
                } else {
                    addNextService()
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post { onConnectionState(device, newState) }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic) {
                reportMap -> HidDescriptor.bytes
                hidInformation -> Hogp.HID_INFORMATION
                protocolModeChar -> byteArrayOf(protocolMode)
                keyboardInput -> ByteArray(Hogp.inputSize(HidDescriptor.REPORT_ID_KEYBOARD))
                mouseInput -> ByteArray(Hogp.inputSize(HidDescriptor.REPORT_ID_MOUSE))
                keyboardOutput -> byteArrayOf(leds.toByte())
                batteryLevel -> byteArrayOf(batteryPercent().toByte())
                pnpId -> Hogp.PNP_ID
                manufacturer -> MANUFACTURER.toByteArray()
                else -> null
            }
            respondRead(device, requestId, offset, value)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val value = when (descriptor.uuid) {
                Hogp.DESC_REPORT_REFERENCE -> reportReferences[descriptor]
                Hogp.DESC_CCCD -> {
                    val id = inputReportId(descriptor.characteristic)
                    val on = id != null && subscriptions[device.address].orEmpty().contains(id)
                    if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                else -> null
            }
            respondRead(device, requestId, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            when (characteristic) {
                keyboardOutput -> Hogp.ledsFromOutput(value)?.let(::onLeds)
                protocolModeChar -> value?.firstOrNull()?.let { protocolMode = it }
                controlPoint -> Log.d(TAG, "HID control point ${value?.firstOrNull()}")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val id = inputReportId(descriptor.characteristic)
            if (descriptor.uuid == Hogp.DESC_CCCD && id != null) {
                val enabled = Hogp.cccdEnabled(value)
                subscriptions.compute(device.address) { _, ids -> if (enabled) ids.orEmpty() + id else ids.orEmpty() - id }
                Log.i(TAG, "${device.address} notifications for report $id: $enabled")
                if (enabled) main.post { becomeHost(device) }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(notifyLock) {
                notifyInFlight = false
                notifyLock.notifyAll()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "MTU $mtu with ${device.address}")
        }
    }

    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray?) {
        val s = server ?: return
        if (value == null) {
            s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            return
        }
        val slice = Hogp.readSlice(value, offset)
        if (slice == null) {
            s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        } else {
            s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }
    }

    private fun onConnectionState(device: BluetoothDevice, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "LE connection from ${device.address} (bond ${device.bondState})")
                // A bonded host keeps its CCCDs across connections and may not write them again.
                if (host == null && device.address in knownHosts && device.bondState == BluetoothDevice.BOND_BONDED) {
                    subscriptions[device.address] = setOf(HidDescriptor.REPORT_ID_KEYBOARD, HidDescriptor.REPORT_ID_MOUSE)
                    becomeHost(device)
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "LE disconnect ${device.address}")
                subscriptions.remove(device.address)
                if (host?.address == device.address) {
                    host = null
                    closePriorityClient()
                    _state.update { it.copy(host = null) }
                    if (running) {
                        setStatus(HidStatus.READY)
                        if (!paused && servicesReady) startAdvertising()
                    }
                }
            }
        }
    }

    private fun becomeHost(device: BluetoothDevice) {
        if (!running) return
        if (host?.address == device.address) return
        host = device
        if (device.bondState == BluetoothDevice.BOND_BONDED && knownHosts.add(device.address)) {
            prefs.edit { putStringSet(K_HOSTS, knownHosts.toSet()) }
        }
        stopAdvertising()
        // Hold the link from the server side too, so closing the priority client cannot drop it.
        runCatching { server?.connect(device, false) }
        requestHighPriority(device)
        _state.update { it.copy(host = device.toHost(), error = null) }
        setStatus(HidStatus.CONNECTED)
    }

    /**
     * A GATT server cannot ask for connection parameters itself, so open a client on the same
     * link and request [BluetoothGatt.CONNECTION_PRIORITY_HIGH] (shortest connection interval).
     */
    private fun requestHighPriority(device: BluetoothDevice) {
        closePriorityClient()
        priorityClient = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    Log.i(TAG, "requestConnectionPriority(HIGH) = $ok")
                }
            }

            override fun onServiceChanged(gatt: BluetoothGatt) = Unit
        }, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closePriorityClient() {
        priorityClient?.let { runCatching { it.disconnect(); it.close() } }
        priorityClient = null
    }

    private fun onLeds(value: Int) {
        leds = value
        main.post {
            _state.update { it.copy(leds = value) }
            onLedsChanged?.invoke(value)
        }
    }

    override fun send(reportId: Int, data: ByteArray): Boolean {
        val device = host ?: return false
        val s = server ?: return false
        val characteristic = if (reportId == HidDescriptor.REPORT_ID_MOUSE) mouseInput else keyboardInput
        if (reportId !in subscriptions[device.address].orEmpty()) return false
        repeat(SEND_ATTEMPTS) {
            if (!awaitNotifySlot()) Log.w(TAG, "onNotificationSent timed out")
            synchronized(notifyLock) { notifyInFlight = true }
            if (notify(s, device, characteristic, data)) return true
            synchronized(notifyLock) {
                notifyInFlight = false
                notifyLock.notifyAll()
            }
        }
        return false
    }

    /** Waits until the previous notification left the stack; false on timeout. */
    private fun awaitNotifySlot(): Boolean = synchronized(notifyLock) {
        val deadline = System.nanoTime() + NOTIFY_TIMEOUT_MS * 1_000_000
        while (notifyInFlight) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return@synchronized false
            notifyLock.wait(left)
        }
        true
    }

    private fun notify(s: BluetoothGattServer, device: BluetoothDevice, c: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, c, false, data) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.setValue(data)
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, c, false)
            }
        } catch (e: SecurityException) {
            false
        }

    private fun startAdvertising() {
        if (!running || paused || advertising || host != null) return
        val advertiser = adapter?.bluetoothLeAdvertiser
            ?: return setStatus(HidStatus.UNSUPPORTED, "This device cannot advertise over Bluetooth LE")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Hogp.SERVICE_HID))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(advertiseWithName).build()
        advertising = true
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            advertising = false
            setStatus(HidStatus.PERMISSION_REQUIRED)
        }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            main.post {
                Log.i(TAG, "Advertising HID service (name=$advertiseWithName)")
                if (host == null) setStatus(HidStatus.READY)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post {
                advertising = false
                when {
                    errorCode == ADVERTISE_FAILED_ALREADY_STARTED -> advertising = true
                    // A long Bluetooth name does not fit in the 31-byte scan response.
                    errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && advertiseWithName -> {
                        advertiseWithName = false
                        startAdvertising()
                    }
                    else -> setStatus(HidStatus.ERROR, "Bluetooth LE advertising failed ($errorCode)")
                }
            }
        }
    }

    private fun teardown() {
        stopAdvertising()
        closePriorityClient()
        server?.let { s ->
            runCatching {
                host?.let(s::cancelConnection)
                s.clearServices()
                s.close()
            }
        }
        server = null
        servicesReady = false
        pendingServices.clear()
        reportReferences.clear()
        subscriptions.clear()
        host = null
        synchronized(notifyLock) {
            notifyInFlight = false
            notifyLock.notifyAll()
        }
        _state.update { it.copy(host = null) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> if (running) start()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    teardown()
                    if (running) setStatus(HidStatus.BLUETOOTH_OFF)
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun batteryPercent(): Int =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 100

    private fun BluetoothDevice.toHost() = HostDevice(
        address = address,
        name = name?.takeIf { it.isNotBlank() } ?: address,
        bonded = bondState == BluetoothDevice.BOND_BONDED,
    )

    private fun setStatus(status: HidStatus, error: String? = null) {
        _state.update { it.copy(status = status, error = error) }
    }

    private companion object {
        const val TAG = "BleHid"
        const val K_HOSTS = "known_hosts"
        const val MANUFACTURER = "DroidPad"
        const val NOTIFY_TIMEOUT_MS = 50L
        const val SEND_ATTEMPTS = 3
    }
}
