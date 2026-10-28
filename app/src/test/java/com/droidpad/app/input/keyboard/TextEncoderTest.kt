package com.droidpad.app.input.keyboard

import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEncoderTest {
    private val toggle = LanguageToggleKey.SHIFT_SPACE.stroke

    @Test
    fun switchesToKoreanOnceForHangulRun() {
        val e = TextEncoder()
        assertEquals(listOf(toggle, KeyStroke(KeyCodes.letter('r')), KeyStroke(KeyCodes.letter('k'))), e.encode('가'))
        assertEquals(HostLanguage.KOREAN, e.hostLanguage)
        assertEquals(2, e.encode('가').size)
    }

    @Test
    fun switchesBackForLatinLettersButNotForDigitsOrSymbols() {
        val e = TextEncoder(hostLanguage = HostLanguage.KOREAN)
        assertEquals(listOf(KeyStroke(KeyCodes.NUM_1)), e.encode('1'))
        assertEquals(HostLanguage.KOREAN, e.hostLanguage)
        assertEquals(listOf(toggle, KeyStroke(KeyCodes.A)), e.encode('a'))
        assertEquals(HostLanguage.ENGLISH, e.hostLanguage)
    }

    @Test
    fun noAutoSwitchWhenDisabled() {
        val e = TextEncoder(autoSwitch = false)
        assertEquals(2, e.encode('가').size)
        assertEquals(HostLanguage.ENGLISH, e.hostLanguage)
    }

    @Test
    fun customToggleKey() {
        val e = TextEncoder(toggleKey = LanguageToggleKey.HANGUL_KEY)
        assertEquals(KeyStroke(KeyCodes.LANG1), e.encode('한').first())
        assertEquals(KeyStroke(0, Modifier.RIGHT_ALT), LanguageToggleKey.RIGHT_ALT.stroke)
    }

    @Test
    fun unsupportedCharacters() {
        val e = TextEncoder()
        assertTrue(e.encode('\uD83D').isEmpty())
        assertTrue(e.encode('漢').isEmpty())
        assertEquals(false, e.isSupported('é'))
        assertEquals(true, e.isSupported('ㅎ'))
    }

    @Test
    fun physicalKeyForShortcuts() {
        val e = TextEncoder()
        assertEquals(KeyStroke(KeyCodes.letter('c')), e.physicalKey('C'))
        // Ctrl+ㅊ on a Korean IME is the physical C key.
        assertEquals(KeyStroke(KeyCodes.letter('c')), e.physicalKey('ㅊ'))
        assertEquals(HostLanguage.ENGLISH, e.hostLanguage)
    }

    @Test
    fun toggleStrokeFlipsLanguage() {
        val e = TextEncoder()
        assertEquals(toggle, e.toggleStroke())
        assertEquals(HostLanguage.KOREAN, e.hostLanguage)
        e.toggleStroke()
        assertEquals(HostLanguage.ENGLISH, e.hostLanguage)
    }
}
