package com.droidpad.app.input

import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.KeyboardLed
import com.droidpad.app.hid.Modifier
import com.droidpad.app.hid.MouseButton
import com.droidpad.app.hid.RecordingTransport
import com.droidpad.app.hid.directSender
import com.droidpad.app.input.keyboard.HostLanguage
import com.droidpad.app.input.touchpad.TouchpadAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputControllerTest {
    private val t = RecordingTransport()
    private val controller = InputController(directSender(t))
    private var editorResets = 0

    init {
        controller.bindEditor { editorResets++ }
    }

    /** Key-down reports only (skip the all-zero releases). */
    private fun pressed() = t.keyboard.filter { r -> r.any { it != 0 } }.map { it[0] and 0xFF to it[2] }

    @Test
    fun typesEditorText() {
        controller.onEditorText("a")
        controller.onEditorText("aB")
        assertEquals(listOf(0 to KeyCodes.A, Modifier.LEFT_SHIFT to KeyCodes.letter('b')), pressed())
        assertEquals("aB", controller.mirrored.value)
    }

    @Test
    fun ctrlLatchTurnsNextCharacterIntoShortcutOnce() {
        controller.cycleModifier(ModifierKey.CTRL)
        controller.onEditorText("c")
        assertEquals(listOf(Modifier.LEFT_CTRL to KeyCodes.letter('c')), pressed())
        assertEquals(LatchState.OFF, controller.modifiers.value[ModifierKey.CTRL])
        assertEquals(1, editorResets)
        assertEquals("", controller.mirrored.value)
    }

    @Test
    fun lockedModifierStaysActive() {
        controller.cycleModifier(ModifierKey.SHIFT)
        controller.cycleModifier(ModifierKey.SHIFT)
        assertEquals(LatchState.LOCKED, controller.modifiers.value[ModifierKey.SHIFT])
        controller.sendKey(KeyStroke(KeyCodes.RIGHT))
        controller.sendKey(KeyStroke(KeyCodes.RIGHT))
        assertEquals(List(2) { Modifier.LEFT_SHIFT to KeyCodes.RIGHT }, pressed())
        controller.cycleModifier(ModifierKey.SHIFT)
        assertEquals(0, controller.activeModifierMask)
    }

    @Test
    fun specialKeyEndsHostCompositionAndResetsEditor() {
        controller.onEditorText("한")
        controller.sendKey(KeyStroke(KeyCodes.ENTER))
        assertEquals("", controller.mirrored.value)
        assertEquals(1, editorResets)
        t.reports.clear()
        // A fresh syllable after Enter needs no Backspaces.
        controller.onEditorText("ㄱ")
        assertEquals(listOf(0 to KeyCodes.letter('r')), pressed())
    }

    @Test
    fun ctrlClickHoldsModifierAroundClick() {
        controller.cycleModifier(ModifierKey.CTRL)
        controller.onTouchpad(TouchpadAction.Click(MouseButton.LEFT))
        assertEquals(Modifier.LEFT_CTRL, t.keyboard.first()[0])
        assertEquals(List(8) { 0 }, t.keyboard.last())
        assertEquals(listOf(1, 0), t.mouse.map { it[0] })
        assertEquals(0, controller.activeModifierMask)
    }

    @Test
    fun toggleLanguageSendsToggleAndFlipsState() {
        controller.toggleLanguage()
        assertEquals(listOf(Modifier.LEFT_SHIFT to KeyCodes.SPACE), pressed())
        assertEquals(HostLanguage.KOREAN, controller.hostLanguage.value)
        controller.syncLanguage(HostLanguage.ENGLISH)
        assertEquals(HostLanguage.ENGLISH, controller.hostLanguage.value)
        assertEquals(1, pressed().size)
    }

    @Test
    fun capsLockLedInvertsShift() {
        controller.onHostLeds(KeyboardLed.CAPS_LOCK)
        assertTrue(controller.capsLock.value)
        controller.onEditorText("a")
        assertEquals(listOf(Modifier.LEFT_SHIFT to KeyCodes.A), pressed())
    }

    @Test
    fun unsupportedCharactersAreCounted() {
        controller.onEditorText("😀")
        assertEquals(1, controller.unsupportedCount.value)
        assertTrue(t.keyboard.isEmpty())
    }

    @Test
    fun hostResetReleasesEverything() {
        controller.cycleModifier(ModifierKey.ALT)
        controller.onHostReset()
        assertEquals(0, controller.activeModifierMask)
        assertEquals(List(8) { 0 }, t.keyboard.last())
        assertEquals(1, editorResets)
    }

    @Test
    fun dragWithOnceModifierReleasesTheModifier() {
        controller.cycleModifier(ModifierKey.SHIFT)
        controller.onTouchpad(TouchpadAction.Press(MouseButton.LEFT))
        // The latch is cleared mid-drag (e.g. by the user); Release must still lift Shift.
        controller.clearModifiers()
        controller.onTouchpad(TouchpadAction.Release(MouseButton.LEFT))
        assertEquals(List(8) { 0 }, t.keyboard.last())
        assertEquals(Modifier.LEFT_SHIFT, t.keyboard.first()[0])
    }

    @Test
    fun hostResetKeepsAssumedLanguage() {
        controller.onEditorText("한")
        controller.onHostReset()
        assertEquals(HostLanguage.KOREAN, controller.hostLanguage.value)
    }

    @Test
    fun heldKeyReleasesWhatWasSent() {
        controller.cycleModifier(ModifierKey.SHIFT)
        controller.keyDown(KeyStroke(KeyCodes.RIGHT))
        assertEquals(Modifier.LEFT_SHIFT to KeyCodes.RIGHT, pressed().last())
        controller.keyUp(KeyStroke(KeyCodes.RIGHT))
        assertEquals(List(8) { 0 }, t.keyboard.last())
        assertEquals(LatchState.OFF, controller.modifiers.value[ModifierKey.SHIFT])
    }
}
