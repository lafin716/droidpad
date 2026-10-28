package com.droidpad.app.hid

/** Bit flags for byte 0 of the keyboard input report. */
object Modifier {
    const val NONE = 0
    const val LEFT_CTRL = 0x01
    const val LEFT_SHIFT = 0x02
    const val LEFT_ALT = 0x04
    const val LEFT_GUI = 0x08
    const val RIGHT_CTRL = 0x10
    const val RIGHT_SHIFT = 0x20
    const val RIGHT_ALT = 0x40
    const val RIGHT_GUI = 0x80
}

/** Mouse button bit flags for byte 0 of the mouse input report. */
object MouseButton {
    const val NONE = 0
    const val LEFT = 0x01
    const val RIGHT = 0x02
    const val MIDDLE = 0x04
    const val BACK = 0x08
    const val FORWARD = 0x10
}

/** Keyboard LED bits from the host output report. */
object KeyboardLed {
    const val NUM_LOCK = 0x01
    const val CAPS_LOCK = 0x02
    const val SCROLL_LOCK = 0x04
}

object HidReports {
    fun keyboard(modifiers: Int, vararg usages: Int): ByteArray {
        require(usages.size <= 6) { "At most 6 simultaneous keys" }
        val out = ByteArray(HidDescriptor.KEYBOARD_INPUT_SIZE)
        out[0] = (modifiers and 0xFF).toByte()
        usages.forEachIndexed { i, u -> out[2 + i] = (u and 0xFF).toByte() }
        return out
    }

    fun keyboardRelease(): ByteArray = ByteArray(HidDescriptor.KEYBOARD_INPUT_SIZE)

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0): ByteArray =
        byteArrayOf(
            (buttons and 0x1F).toByte(),
            clamp8(dx).toByte(),
            clamp8(dy).toByte(),
            clamp8(wheel).toByte(),
            clamp8(pan).toByte(),
        )

    fun clamp8(v: Int): Int = v.coerceIn(-127, 127)
}
