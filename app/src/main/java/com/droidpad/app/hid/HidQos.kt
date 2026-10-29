package com.droidpad.app.hid

import android.bluetooth.BluetoothHidDeviceAppQosSettings

/**
 * Labs: outgoing (device → host) QoS declared when registering the HID app. The values go into
 * the L2CAP flow spec of the interrupt channel; hosts may honour, ignore or reject them, so these
 * presets exist to compare pointer latency on real hardware.
 */
enum class HidQosPreset(val params: QosParams?) {
    /** The values from Android's HID device sample, used since v1.0. */
    STANDARD(QosParams(BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11_250)),
    /** Same latency, but declares enough bandwidth for ~125 reports per second. */
    HIGH_BANDWIDTH(QosParams(BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 8_000, 64, 16_000, 11_250)),
    /** High bandwidth and asks for at most 7.5 ms latency. */
    LOW_LATENCY(QosParams(BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 8_000, 64, 16_000, 7_500)),
    /** Asks the host to reserve bandwidth; some hosts refuse the connection. */
    GUARANTEED(QosParams(BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED, 8_000, 64, 16_000, 7_500)),
    /** Declares nothing and lets the Bluetooth stack use its defaults. */
    NONE(null),
}

/** Mirrors [BluetoothHidDeviceAppQosSettings]; rates in bytes/s, latency in µs. */
data class QosParams(
    val serviceType: Int,
    val tokenRate: Int,
    val tokenBucketSize: Int,
    val peakBandwidth: Int,
    val latencyMicros: Int,
    val delayVariation: Int = BluetoothHidDeviceAppQosSettings.MAX,
) {
    fun toSettings() = BluetoothHidDeviceAppQosSettings(
        serviceType, tokenRate, tokenBucketSize, peakBandwidth, latencyMicros, delayVariation,
    )
}
