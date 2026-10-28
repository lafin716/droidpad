package com.droidpad.app.input.keyboard

import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HangulTest {
    @Test
    fun syllables() {
        assertEquals("gks", Hangul.keySequence('한'))
        assertEquals("rmf", Hangul.keySequence('글'))
        assertEquals("rkqt", Hangul.keySequence('값'))
        assertEquals("dml", Hangul.keySequence('의'))
        assertEquals("Tkd", Hangul.keySequence('쌍'))
        assertEquals("dhk", Hangul.keySequence('와'))
        assertEquals("rk", Hangul.keySequence('가'))
        assertEquals("glg", Hangul.keySequence('힣'))
    }

    @Test
    fun compatibilityJamo() {
        assertEquals("r", Hangul.keySequence('ㄱ'))
        assertEquals("R", Hangul.keySequence('ㄲ'))
        assertEquals("rt", Hangul.keySequence('ㄳ'))
        assertEquals("g", Hangul.keySequence('ㅎ'))
        assertEquals("k", Hangul.keySequence('ㅏ'))
        assertEquals("O", Hangul.keySequence('ㅒ'))
        assertEquals("ml", Hangul.keySequence('ㅢ'))
        assertEquals("l", Hangul.keySequence('ㅣ'))
    }

    @Test
    fun nonHangulIsNull() {
        assertNull(Hangul.keySequence('a'))
        assertNull(Hangul.strokes('1'))
    }

    @Test
    fun shiftedJamoUseShift() {
        assertEquals(
            listOf(KeyStroke(KeyCodes.letter('t'), Modifier.LEFT_SHIFT), KeyStroke(KeyCodes.letter('k'))),
            Hangul.strokes('싸'),
        )
    }

    @Test
    fun everySyllableMapsToValidKeys() {
        for (code in 0xAC00..0xD7A3) {
            val keys = Hangul.keySequence(code.toChar())!!
            assert(keys.length in 2..5) { "${code.toChar()} -> $keys" }
            assert(keys.all { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }
}
