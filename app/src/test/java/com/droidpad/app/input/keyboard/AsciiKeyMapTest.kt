package com.droidpad.app.input.keyboard

import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AsciiKeyMapTest {
    private val shift = Modifier.LEFT_SHIFT

    @Test
    fun letters() {
        assertEquals(KeyStroke(KeyCodes.A), AsciiKeyMap.strokeFor('a'))
        assertEquals(KeyStroke(KeyCodes.Z, shift), AsciiKeyMap.strokeFor('Z'))
    }

    @Test
    fun capsLockInvertsShiftForLettersOnly() {
        assertEquals(KeyStroke(KeyCodes.A, shift), AsciiKeyMap.strokeFor('a', capsLock = true))
        assertEquals(KeyStroke(KeyCodes.A), AsciiKeyMap.strokeFor('A', capsLock = true))
        assertEquals(KeyStroke(KeyCodes.NUM_1), AsciiKeyMap.strokeFor('1', capsLock = true))
    }

    @Test
    fun digitsAndShiftedDigits() {
        assertEquals(KeyStroke(KeyCodes.NUM_0), AsciiKeyMap.strokeFor('0'))
        assertEquals(KeyStroke(KeyCodes.NUM_1 + 8), AsciiKeyMap.strokeFor('9'))
        assertEquals(KeyStroke(KeyCodes.NUM_1 + 1, shift), AsciiKeyMap.strokeFor('@'))
        assertEquals(KeyStroke(KeyCodes.NUM_0, shift), AsciiKeyMap.strokeFor(')'))
    }

    @Test
    fun symbolsAndWhitespace() {
        assertEquals(KeyStroke(KeyCodes.SLASH, shift), AsciiKeyMap.strokeFor('?'))
        assertEquals(KeyStroke(KeyCodes.GRAVE, shift), AsciiKeyMap.strokeFor('~'))
        assertEquals(KeyStroke(KeyCodes.SPACE), AsciiKeyMap.strokeFor(' '))
        assertEquals(KeyStroke(KeyCodes.ENTER), AsciiKeyMap.strokeFor('\n'))
    }

    @Test
    fun everyPrintableAsciiIsMapped() {
        for (c in ' '..'~') assertNotNull("missing $c", AsciiKeyMap.strokeFor(c))
    }

    @Test
    fun unsupportedReturnsNull() {
        assertNull(AsciiKeyMap.strokeFor('é'))
        assertNull(AsciiKeyMap.strokeFor('€'))
    }
}
