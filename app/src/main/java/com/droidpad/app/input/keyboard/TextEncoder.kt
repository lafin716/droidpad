package com.droidpad.app.input.keyboard

import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier

/** Input language currently assumed to be active on the host's keyboard layout. */
enum class HostLanguage { ENGLISH, KOREAN }

/** Key combination that toggles 한/영 on the host. */
enum class LanguageToggleKey(val stroke: KeyStroke) {
    /** Default for Samsung Galaxy Tab / Android physical keyboards. */
    SHIFT_SPACE(KeyStroke(KeyCodes.SPACE, Modifier.LEFT_SHIFT)),
    /** Dedicated 한/영 key (HID LANG1) found on Korean keyboards. */
    HANGUL_KEY(KeyStroke(KeyCodes.LANG1)),
    /** Right Alt, used as 한/영 by Windows with a 101/104-key Korean layout. */
    RIGHT_ALT(KeyStroke(0, Modifier.RIGHT_ALT)),
    CTRL_SPACE(KeyStroke(KeyCodes.SPACE, Modifier.LEFT_CTRL)),
}

/**
 * Converts characters to HID strokes, inserting a 한/영 toggle whenever the character requires a
 * different host language than the one currently assumed.
 */
class TextEncoder(
    var autoSwitch: Boolean = true,
    var toggleKey: LanguageToggleKey = LanguageToggleKey.SHIFT_SPACE,
    var hostLanguage: HostLanguage = HostLanguage.ENGLISH,
    var capsLock: Boolean = false,
) {
    /** Strokes for [c], or an empty list when [c] cannot be typed on a US/2-beolsik layout. */
    fun encode(c: Char): List<KeyStroke> {
        val hangul = Hangul.strokes(c)
        if (hangul != null) return switchTo(HostLanguage.KOREAN) + hangul
        val ascii = AsciiKeyMap.strokeFor(c, capsLock) ?: return emptyList()
        return if (AsciiKeyMap.isLatinLetter(c)) switchTo(HostLanguage.ENGLISH) + ascii else listOf(ascii)
    }

    fun isSupported(c: Char): Boolean = Hangul.isHangul(c) || AsciiKeyMap.strokeFor(c) != null

    /** Strokes for [c] as a physical key (used for shortcuts); never toggles the host language. */
    fun physicalKey(c: Char): KeyStroke? =
        Hangul.strokes(c)?.firstOrNull()?.takeIf { Hangul.keySequence(c)!!.length == 1 }
            ?: AsciiKeyMap.strokeFor(c.lowercaseChar())

    fun toggleStroke(): KeyStroke {
        hostLanguage = if (hostLanguage == HostLanguage.ENGLISH) HostLanguage.KOREAN else HostLanguage.ENGLISH
        return toggleKey.stroke
    }

    private fun switchTo(target: HostLanguage): List<KeyStroke> {
        if (!autoSwitch || hostLanguage == target) return emptyList()
        hostLanguage = target
        return listOf(toggleKey.stroke)
    }
}
