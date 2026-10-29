package com.droidpad.app.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HogpTest {
    @Test
    fun uuidsUseBluetoothBase() {
        assertEquals("00001812-0000-1000-8000-00805f9b34fb", Hogp.SERVICE_HID.toString())
        assertEquals("00002a4d-0000-1000-8000-00805f9b34fb", Hogp.CHAR_REPORT.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", Hogp.DESC_CCCD.toString())
    }

    @Test
    fun reportReferenceIsIdThenType() {
        assertArrayEquals(byteArrayOf(2, 1), Hogp.reportReference(HidDescriptor.REPORT_ID_MOUSE, Hogp.REPORT_TYPE_INPUT))
        assertArrayEquals(byteArrayOf(1, 2), Hogp.reportReference(HidDescriptor.REPORT_ID_KEYBOARD, Hogp.REPORT_TYPE_OUTPUT))
    }

    @Test
    fun inputSizeMatchesDescriptor() {
        assertEquals(HidDescriptor.MOUSE_INPUT_SIZE, Hogp.inputSize(HidDescriptor.REPORT_ID_MOUSE))
        assertEquals(HidDescriptor.KEYBOARD_INPUT_SIZE, Hogp.inputSize(HidDescriptor.REPORT_ID_KEYBOARD))
    }

    @Test
    fun longReadsSliceTheReportMap() {
        val map = HidDescriptor.bytes
        assertArrayEquals(map, Hogp.readSlice(map, 0))
        assertArrayEquals(map.copyOfRange(22, map.size), Hogp.readSlice(map, 22))
        assertEquals(0, Hogp.readSlice(map, map.size)!!.size)
        assertNull(Hogp.readSlice(map, map.size + 1))
        assertNull(Hogp.readSlice(map, -1))
    }

    @Test
    fun cccdEnablesOnNotifyOrIndicate() {
        assertTrue(Hogp.cccdEnabled(byteArrayOf(1, 0)))
        assertTrue(Hogp.cccdEnabled(byteArrayOf(2, 0)))
        assertFalse(Hogp.cccdEnabled(byteArrayOf(0, 0)))
        assertFalse(Hogp.cccdEnabled(byteArrayOf()))
        assertFalse(Hogp.cccdEnabled(null))
    }

    @Test
    fun ledsKeepLowThreeBits() {
        assertEquals(0b010, Hogp.ledsFromOutput(byteArrayOf(0b1010)))
        assertEquals(0b111, Hogp.ledsFromOutput(byteArrayOf(-1)))
        assertNull(Hogp.ledsFromOutput(byteArrayOf()))
        assertNull(Hogp.ledsFromOutput(null))
    }

    @Test
    fun mergeUsesClassicPairingAndActiveLink() {
        val host = HostDevice("AA:BB:CC:DD:EE:FF", "Tab", true)
        val bonded = listOf(host)
        val classic = HidState(status = HidStatus.READY, bondedDevices = bonded, reconnectAttempt = 3, leds = 1)
        val ble = HidState(status = HidStatus.CONNECTED, host = host, leds = 2, transport = HidTransport.BLE)

        val c = HidLink.merge(HidTransport.CLASSIC, classic, ble)
        assertEquals(classic, c)

        val b = HidLink.merge(HidTransport.BLE, classic, ble)
        assertEquals(HidStatus.CONNECTED, b.status)
        assertEquals(host, b.host)
        assertEquals(2, b.leds)
        assertEquals(0, b.reconnectAttempt)
        assertEquals(bonded, b.bondedDevices)
        assertEquals(HidTransport.BLE, b.transport)
    }
}
