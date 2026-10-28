package com.droidpad.app.input.keyboard

import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

class TextMirrorTest {
    private val encoder = TextEncoder()
    private val sent = ArrayList<KeyStroke>()
    private var unsupported = 0
    private val mirror = TextMirror(encoder, { sent += it }, { unsupported++ })

    /** Renders strokes as a readable string: letters, ⌫ for backspace, ⇧␣ for the toggle. */
    private fun keys(): String = sent.joinToString("") { s ->
        when {
            s == TextMirror.BACKSPACE -> "⌫"
            s == LanguageToggleKey.SHIFT_SPACE.stroke -> "⇧␣"
            s.usage == KeyCodes.SPACE -> " "
            s.usage in KeyCodes.A..KeyCodes.Z -> {
                val c = 'a' + (s.usage - KeyCodes.A)
                if (s.modifiers and Modifier.LEFT_SHIFT != 0) c.uppercase() else c.toString()
            }
            else -> "#${s.usage}"
        }
    }.also { sent.clear() }

    private fun type(vararg states: String) = states.forEach(mirror::update)

    @Test
    fun englishTyping() {
        type("H", "Hi", "Hi ")
        assertEquals("Hi ", keys())
    }

    @Test
    fun koreanComposingSendsOnlyNewJamo() {
        // Samsung IME edit sequence for "한글".
        type("ㅎ", "하", "한", "한ㄱ", "한그", "한글")
        assertEquals("⇧␣gksrmf", keys())
        assertEquals(true, mirror.hostComposing)
    }

    @Test
    fun finalConsonantMovesToNextSyllable() {
        // 간 + ㅏ → 가나: the host IME does the same with one more 'k'.
        type("ㄱ", "가", "간", "가나")
        assertEquals("⇧␣rksk", keys())
    }

    @Test
    fun backspaceInsideComposingSyllableRemovesOneJamo() {
        type("ㅎ", "하", "한")
        keys()
        type("하")
        assertEquals("⌫", keys())
        type("ㅎ")
        assertEquals("⌫", keys())
        type("")
        assertEquals("⌫", keys())
    }

    @Test
    fun backspaceOfCommittedSyllableIsOneKey() {
        type("ㅎ", "하", "한", "한 ")
        keys()
        type("한")
        assertEquals("⌫", keys())
        // "한" is now committed on the host, so deleting it is a single Backspace.
        type("")
        assertEquals("⌫", keys())
    }

    @Test
    fun mixedLanguagesToggleOnlyWhenNeeded() {
        type("a", "a1", "a1ㄱ", "a1가", "a1가b")
        assertEquals("a#${KeyCodes.NUM_1}⇧␣rk⇧␣b", keys())
    }

    @Test
    fun autocorrectReplacementDeletesDifferingSuffix() {
        type("teh")
        keys()
        type("the ")
        assertEquals("⌫⌫he ", keys())
    }

    @Test
    fun unsupportedCharactersAreSkippedAndNotDeletedLater() {
        type("a")
        keys()
        type("a😀")
        assertEquals("", keys())
        assertEquals(1, unsupported)
        type("")
        assertEquals("⌫", keys())
    }

    @Test
    fun endCompositionMakesNextJamoANewSyllable() {
        type("ㅎ", "하")
        keys()
        mirror.endComposition()
        mirror.reset()
        type("ㄴ")
        assertEquals("s", keys())
    }

    @Test
    fun emojiCountsAsOneUnsupportedCharacter() {
        mirror.update("a\uD83D\uDE00")
        assertEquals(1, unsupported)
    }

    @Test
    fun replacedEmojiIsCountedAgain() {
        mirror.update("\uD83D\uDE00")
        mirror.update("\uD83D\uDE01")
        assertEquals(2, unsupported)
    }
}
