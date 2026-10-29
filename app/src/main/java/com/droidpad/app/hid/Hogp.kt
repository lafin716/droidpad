package com.droidpad.app.hid

import java.util.Locale
import java.util.UUID

/** How DroidPad reaches the host: classic Bluetooth HID, or HID over GATT (Labs). */
enum class HidTransport { CLASSIC, BLE }

/**
 * Constants and attribute values for HID over GATT (HOGP). Kept free of Android types so the
 * encoding can be unit tested; [BleHidPeripheral] builds the GATT database from them.
 */
object Hogp {
    val SERVICE_HID: UUID = uuid16(0x1812)
    val SERVICE_BATTERY: UUID = uuid16(0x180F)
    val SERVICE_DEVICE_INFO: UUID = uuid16(0x180A)

    val CHAR_HID_INFORMATION: UUID = uuid16(0x2A4A)
    val CHAR_REPORT_MAP: UUID = uuid16(0x2A4B)
    val CHAR_HID_CONTROL_POINT: UUID = uuid16(0x2A4C)
    val CHAR_REPORT: UUID = uuid16(0x2A4D)
    val CHAR_PROTOCOL_MODE: UUID = uuid16(0x2A4E)
    val CHAR_BATTERY_LEVEL: UUID = uuid16(0x2A19)
    val CHAR_MANUFACTURER_NAME: UUID = uuid16(0x2A29)
    val CHAR_PNP_ID: UUID = uuid16(0x2A50)

    val DESC_CCCD: UUID = uuid16(0x2902)
    val DESC_REPORT_REFERENCE: UUID = uuid16(0x2908)

    const val REPORT_TYPE_INPUT = 1
    const val REPORT_TYPE_OUTPUT = 2
    const val PROTOCOL_MODE_REPORT = 1

    /** bcdHID 1.11, country code 0, flags: normally connectable. */
    val HID_INFORMATION = byteArrayOf(0x11, 0x01, 0x00, 0x02)

    /** Vendor ID source 2 (USB-IF), vendor 0x1D6B (Linux Foundation), product 0x0246, version 1.0. */
    val PNP_ID = byteArrayOf(0x02, 0x6B, 0x1D, 0x46, 0x02, 0x00, 0x01)

    fun reportReference(reportId: Int, type: Int) = byteArrayOf(reportId.toByte(), type.toByte())

    /** Size of the input report (without the report id byte) for [reportId]. */
    fun inputSize(reportId: Int): Int =
        if (reportId == HidDescriptor.REPORT_ID_MOUSE) HidDescriptor.MOUSE_INPUT_SIZE else HidDescriptor.KEYBOARD_INPUT_SIZE

    /** The part of [value] a long read starting at [offset] returns; null when the offset is invalid. */
    fun readSlice(value: ByteArray, offset: Int): ByteArray? =
        if (offset < 0 || offset > value.size) null else value.copyOfRange(offset, value.size)

    /** True when a CCCD write enables notifications (bit 0) or indications (bit 1). */
    fun cccdEnabled(value: ByteArray?): Boolean =
        value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0

    /** Keyboard LED bitmask (Num/Caps/Scroll) from an output report written by the host. */
    fun ledsFromOutput(value: ByteArray?): Int? =
        value?.firstOrNull()?.let { it.toInt() and 0x07 }

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))
}
