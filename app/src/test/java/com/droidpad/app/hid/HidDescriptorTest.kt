package com.droidpad.app.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Parses the short items of the report descriptor and checks the report sizes we send. */
class HidDescriptorTest {
    private data class Totals(val inputBits: Int, val outputBits: Int)

    private fun totalsByReportId(): Map<Int, Totals> {
        val bytes = HidDescriptor.bytes
        var i = 0
        var reportSize = 0
        var reportCount = 0
        var reportId = 0
        var depth = 0
        val input = HashMap<Int, Int>()
        val output = HashMap<Int, Int>()
        while (i < bytes.size) {
            val prefix = bytes[i].toInt() and 0xFF
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            var value = 0
            for (b in 0 until size) value = value or ((bytes[i + 1 + b].toInt() and 0xFF) shl (8 * b))
            when (prefix and 0xFC) {
                0x74 -> reportSize = value
                0x94 -> reportCount = value
                0x84 -> reportId = value
                0x80 -> input.merge(reportId, reportSize * reportCount, Int::plus)
                0x90 -> output.merge(reportId, reportSize * reportCount, Int::plus)
                0xA0 -> depth++
                0xC0 -> depth--
            }
            i += 1 + size
        }
        assertEquals("collections must be balanced", 0, depth)
        return (input.keys + output.keys).associateWith { Totals(input[it] ?: 0, output[it] ?: 0) }
    }

    @Test
    fun keyboardReportIs8BytesWith1ByteLedOutput() {
        val t = totalsByReportId().getValue(HidDescriptor.REPORT_ID_KEYBOARD)
        assertEquals(HidDescriptor.KEYBOARD_INPUT_SIZE * 8, t.inputBits)
        assertEquals(8, t.outputBits)
    }

    @Test
    fun mouseReportIs5Bytes() {
        val t = totalsByReportId().getValue(HidDescriptor.REPORT_ID_MOUSE)
        assertEquals(HidDescriptor.MOUSE_INPUT_SIZE * 8, t.inputBits)
        assertEquals(0, t.outputBits)
    }

    @Test
    fun reportBuildersMatchDescriptorSizes() {
        assertEquals(HidDescriptor.KEYBOARD_INPUT_SIZE, HidReports.keyboard(0, KeyCodes.A).size)
        assertEquals(HidDescriptor.MOUSE_INPUT_SIZE, HidReports.mouse(0, 1, 1).size)
    }

    @Test
    fun keyboardReportLayout() {
        val r = HidReports.keyboard(Modifier.LEFT_SHIFT or Modifier.LEFT_CTRL, KeyCodes.A, KeyCodes.ENTER)
        assertArrayEquals(byteArrayOf(0x03, 0, 0x04, 0x28, 0, 0, 0, 0), r)
    }

    @Test
    fun mouseReportClampsToSignedByte() {
        val r = HidReports.mouse(MouseButton.LEFT, 500, -500, 3, -2)
        assertArrayEquals(byteArrayOf(1, 127, -127, 3, -2), r)
    }

    @Test(expected = IllegalArgumentException::class)
    fun keyboardRejectsMoreThanSixKeys() {
        HidReports.keyboard(0, 4, 5, 6, 7, 8, 9, 10)
    }
}
