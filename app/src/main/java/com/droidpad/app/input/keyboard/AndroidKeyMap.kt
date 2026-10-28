package com.droidpad.app.input.keyboard

import android.view.KeyEvent
import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier

/** Maps Android [KeyEvent] key codes (non-text keys sent by IMEs) to HID strokes. */
object AndroidKeyMap {
    private val keys: Map<Int, Int> = buildMap {
        put(KeyEvent.KEYCODE_ENTER, KeyCodes.ENTER)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyCodes.ENTER)
        put(KeyEvent.KEYCODE_DEL, KeyCodes.BACKSPACE)
        put(KeyEvent.KEYCODE_FORWARD_DEL, KeyCodes.DELETE)
        put(KeyEvent.KEYCODE_TAB, KeyCodes.TAB)
        put(KeyEvent.KEYCODE_ESCAPE, KeyCodes.ESCAPE)
        put(KeyEvent.KEYCODE_SPACE, KeyCodes.SPACE)
        put(KeyEvent.KEYCODE_DPAD_LEFT, KeyCodes.LEFT)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, KeyCodes.RIGHT)
        put(KeyEvent.KEYCODE_DPAD_UP, KeyCodes.UP)
        put(KeyEvent.KEYCODE_DPAD_DOWN, KeyCodes.DOWN)
        put(KeyEvent.KEYCODE_MOVE_HOME, KeyCodes.HOME)
        put(KeyEvent.KEYCODE_MOVE_END, KeyCodes.END)
        put(KeyEvent.KEYCODE_PAGE_UP, KeyCodes.PAGE_UP)
        put(KeyEvent.KEYCODE_PAGE_DOWN, KeyCodes.PAGE_DOWN)
        put(KeyEvent.KEYCODE_INSERT, KeyCodes.INSERT)
        put(KeyEvent.KEYCODE_CAPS_LOCK, KeyCodes.CAPS_LOCK)
        put(KeyEvent.KEYCODE_KANA, KeyCodes.LANG1)
        put(KeyEvent.KEYCODE_LANGUAGE_SWITCH, KeyCodes.LANG1)
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, KeyCodes.function(i + 1))
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, KeyCodes.A + i)
        put(KeyEvent.KEYCODE_0, KeyCodes.NUM_0)
        for (i in 1..9) put(KeyEvent.KEYCODE_1 + i - 1, KeyCodes.NUM_1 + i - 1)
    }

    fun usageFor(keyCode: Int): Int? = keys[keyCode]

    fun modifiersFor(metaState: Int): Int {
        var m = Modifier.NONE
        if (metaState and KeyEvent.META_CTRL_ON != 0) m = m or Modifier.LEFT_CTRL
        if (metaState and KeyEvent.META_SHIFT_ON != 0) m = m or Modifier.LEFT_SHIFT
        if (metaState and KeyEvent.META_ALT_ON != 0) m = m or Modifier.LEFT_ALT
        if (metaState and KeyEvent.META_META_ON != 0) m = m or Modifier.LEFT_GUI
        return m
    }

    fun strokeFor(keyCode: Int, metaState: Int): KeyStroke? =
        usageFor(keyCode)?.let { KeyStroke(it, modifiersFor(metaState)) }
}
