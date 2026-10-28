package com.droidpad.app.input.keyboard

import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier

/**
 * Hangul → Korean standard 2-beolsik (두벌식) key positions.
 *
 * A HID keyboard only sends physical key positions; the host's Korean IME recomposes jamo into
 * syllables. Each key string uses an uppercase letter for a Shift-ed key (e.g. "R" = ㄲ).
 */
object Hangul {
    private const val SYLLABLE_BASE = 0xAC00
    private const val SYLLABLE_LAST = 0xD7A3
    private const val MEDIAL_COUNT = 21
    private const val FINAL_COUNT = 28
    private const val COMPAT_FIRST = 0x3131
    private const val COMPAT_LAST = 0x3163

    private val INITIALS = arrayOf(
        "r", "R", "s", "e", "E", "f", "a", "q", "Q", "t", "T", "d", "w", "W", "c", "z", "x", "v", "g",
    )
    private val MEDIALS = arrayOf(
        "k", "o", "i", "O", "j", "p", "u", "P", "h", "hk", "ho", "hl", "y", "n", "nj", "np", "nl", "b", "m", "ml", "l",
    )
    private val FINALS = arrayOf(
        "", "r", "R", "rt", "s", "sw", "sg", "e", "f", "fr", "fa", "fq", "ft", "fx", "fv", "fg",
        "a", "q", "qt", "t", "T", "d", "w", "c", "z", "x", "v", "g",
    )
    /** U+3131 (ㄱ) … U+314E (ㅎ): compatibility consonants in Unicode order. */
    private val COMPAT_CONSONANTS = arrayOf(
        "r", "R", "rt", "s", "sw", "sg", "e", "E", "f", "fr", "fa", "fq", "ft", "fx", "fv", "fg",
        "a", "q", "Q", "qt", "t", "T", "d", "w", "W", "c", "z", "x", "v", "g",
    )

    fun isSyllable(c: Char): Boolean = c.code in SYLLABLE_BASE..SYLLABLE_LAST
    fun isCompatJamo(c: Char): Boolean = c.code in COMPAT_FIRST..COMPAT_LAST
    fun isHangul(c: Char): Boolean = isSyllable(c) || isCompatJamo(c)

    /** 2-beolsik key string for [c] (uppercase = Shift), or null when [c] is not Hangul. */
    fun keySequence(c: Char): String? {
        val code = c.code
        if (isSyllable(c)) {
            val index = code - SYLLABLE_BASE
            val initial = index / (MEDIAL_COUNT * FINAL_COUNT)
            val medial = (index % (MEDIAL_COUNT * FINAL_COUNT)) / FINAL_COUNT
            val final = index % FINAL_COUNT
            return INITIALS[initial] + MEDIALS[medial] + FINALS[final]
        }
        if (isCompatJamo(c)) {
            val offset = code - COMPAT_FIRST
            return if (offset < COMPAT_CONSONANTS.size) COMPAT_CONSONANTS[offset]
            else MEDIALS[offset - COMPAT_CONSONANTS.size]
        }
        return null
    }

    /** HID strokes for [c] on a 2-beolsik layout, or null when [c] is not Hangul. */
    fun strokes(c: Char): List<KeyStroke>? = keySequence(c)?.map { k ->
        KeyStroke(KeyCodes.letter(k), if (k.isUpperCase()) Modifier.LEFT_SHIFT else Modifier.NONE)
    }
}
