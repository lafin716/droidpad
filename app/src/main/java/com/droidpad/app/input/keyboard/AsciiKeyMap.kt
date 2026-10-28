package com.droidpad.app.input.keyboard

import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier

/** Maps printable ASCII to US-layout HID key strokes. */
object AsciiKeyMap {
    private const val SHIFT = Modifier.LEFT_SHIFT

    private val symbols: Map<Char, KeyStroke> = buildMap {
        val shiftedDigits = ")!@#$%^&*("
        for (d in 0..9) {
            val usage = if (d == 0) KeyCodes.NUM_0 else KeyCodes.NUM_1 + (d - 1)
            put('0' + d, KeyStroke(usage))
            put(shiftedDigits[d], KeyStroke(usage, SHIFT))
        }
        fun pair(plain: Char, shifted: Char, usage: Int) {
            put(plain, KeyStroke(usage))
            put(shifted, KeyStroke(usage, SHIFT))
        }
        pair('-', '_', KeyCodes.MINUS)
        pair('=', '+', KeyCodes.EQUAL)
        pair('[', '{', KeyCodes.LEFT_BRACKET)
        pair(']', '}', KeyCodes.RIGHT_BRACKET)
        pair('\\', '|', KeyCodes.BACKSLASH)
        pair(';', ':', KeyCodes.SEMICOLON)
        pair('\'', '"', KeyCodes.APOSTROPHE)
        pair('`', '~', KeyCodes.GRAVE)
        pair(',', '<', KeyCodes.COMMA)
        pair('.', '>', KeyCodes.PERIOD)
        pair('/', '?', KeyCodes.SLASH)
        put(' ', KeyStroke(KeyCodes.SPACE))
        put('\n', KeyStroke(KeyCodes.ENTER))
        put('\t', KeyStroke(KeyCodes.TAB))
    }

    fun isLatinLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /**
     * @param capsLock when the host reports Caps Lock on, Shift is inverted for letters so the
     * host still produces the intended case.
     */
    fun strokeFor(c: Char, capsLock: Boolean = false): KeyStroke? {
        if (isLatinLetter(c)) {
            val upper = c.isUpperCase() != capsLock
            return KeyStroke(KeyCodes.letter(c), if (upper) SHIFT else Modifier.NONE)
        }
        return symbols[c]
    }
}
