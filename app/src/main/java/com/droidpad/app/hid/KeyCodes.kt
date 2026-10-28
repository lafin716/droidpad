package com.droidpad.app.hid

/** USB HID Keyboard/Keypad page (0x07) usage IDs used by DroidPad. */
object KeyCodes {
    const val A = 0x04
    const val Z = 0x1D
    const val NUM_1 = 0x1E
    const val NUM_0 = 0x27
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val MINUS = 0x2D
    const val EQUAL = 0x2E
    const val LEFT_BRACKET = 0x2F
    const val RIGHT_BRACKET = 0x30
    const val BACKSLASH = 0x31
    const val SEMICOLON = 0x33
    const val APOSTROPHE = 0x34
    const val GRAVE = 0x35
    const val COMMA = 0x36
    const val PERIOD = 0x37
    const val SLASH = 0x38
    const val CAPS_LOCK = 0x39
    const val F1 = 0x3A
    const val F12 = 0x45
    const val PRINT_SCREEN = 0x46
    const val SCROLL_LOCK = 0x47
    const val PAUSE = 0x48
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val APPLICATION = 0x65
    /** LANG1 — the Korean "Hangul" (한/영) key. */
    const val LANG1 = 0x90
    /** LANG2 — the Korean "Hanja" key. */
    const val LANG2 = 0x91

    fun letter(c: Char): Int = A + (c.lowercaseChar() - 'a')
    fun function(n: Int): Int {
        require(n in 1..12)
        return F1 + (n - 1)
    }
}

/** A single key press (modifiers + one usage). `usage == 0` means modifier-only press. */
data class KeyStroke(val usage: Int, val modifiers: Int = Modifier.NONE) {
    fun withModifiers(extra: Int): KeyStroke = copy(modifiers = modifiers or extra)
}
