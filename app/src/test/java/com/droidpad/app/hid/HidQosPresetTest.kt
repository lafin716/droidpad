package com.droidpad.app.hid

import android.bluetooth.BluetoothHidDeviceAppQosSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidQosPresetTest {
    @Test
    fun standardKeepsTheValuesUsedBeforePresets() {
        assertEquals(
            QosParams(BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11_250, BluetoothHidDeviceAppQosSettings.MAX),
            HidQosPreset.STANDARD.params,
        )
    }

    @Test
    fun noneDeclaresNothing() {
        assertNull(HidQosPreset.NONE.params)
    }

    @Test
    fun fasterPresetsDeclareEnoughBandwidthForPacedPointerReports() {
        // A mouse report is ~11 bytes on air including L2CAP/HIDP headers.
        val needed = 11 * 1000 / 8 // one report every 8 ms, the fastest interval offered
        listOf(HidQosPreset.HIGH_BANDWIDTH, HidQosPreset.LOW_LATENCY, HidQosPreset.GUARANTEED).forEach {
            val p = it.params!!
            assertTrue("$it tokenRate", p.tokenRate >= needed)
            assertTrue("$it peak", p.peakBandwidth >= needed)
            assertTrue("$it latency", p.latencyMicros <= 11_250)
        }
        assertEquals(BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED, HidQosPreset.GUARANTEED.params!!.serviceType)
    }
}
