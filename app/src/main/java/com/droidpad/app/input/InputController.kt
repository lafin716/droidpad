package com.droidpad.app.input

import com.droidpad.app.hid.HidReportSender
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.KeyboardLed
import com.droidpad.app.hid.Modifier
import com.droidpad.app.input.keyboard.HostLanguage
import com.droidpad.app.input.keyboard.LanguageToggleKey
import com.droidpad.app.input.keyboard.TextEncoder
import com.droidpad.app.input.keyboard.TextMirror
import com.droidpad.app.input.touchpad.TouchpadAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ModifierKey(val mask: Int) {
    CTRL(Modifier.LEFT_CTRL),
    SHIFT(Modifier.LEFT_SHIFT),
    ALT(Modifier.LEFT_ALT),
    META(Modifier.LEFT_GUI),
}

/** OFF → ONCE (applies to the next key) → LOCKED (until tapped again). */
enum class LatchState { OFF, ONCE, LOCKED }

/**
 * Glue between the local UI/IME and the HID sender. Free of Android dependencies so it can be
 * unit-tested; all methods must be called from the same (main) thread.
 */
class InputController(
    private val sender: HidReportSender,
    private val encoder: TextEncoder = TextEncoder(),
) {
    private val _modifiers = MutableStateFlow(ModifierKey.entries.associateWith { LatchState.OFF })
    val modifiers: StateFlow<Map<ModifierKey, LatchState>> = _modifiers.asStateFlow()

    private val _hostLanguage = MutableStateFlow(encoder.hostLanguage)
    val hostLanguage: StateFlow<HostLanguage> = _hostLanguage.asStateFlow()

    private val _capsLock = MutableStateFlow(false)
    val capsLock: StateFlow<Boolean> = _capsLock.asStateFlow()

    /** Text mirrored to the host since the last reset (input preview). */
    private val _mirrored = MutableStateFlow("")
    val mirrored: StateFlow<String> = _mirrored.asStateFlow()

    /** Characters that could not be typed (emoji, CJK other than Hangul…). */
    private val _unsupportedCount = MutableStateFlow(0)
    val unsupportedCount: StateFlow<Int> = _unsupportedCount.asStateFlow()

    private val mirror = TextMirror(
        encoder = encoder,
        emit = { sender.type(it) },
        onUnsupported = { _unsupportedCount.update { n -> n + 1 } },
    )
    private var resetEditor: (() -> Unit)? = null
    private var dragModifierMask = 0
    /** Held keys as pressed by the UI → the stroke actually sent (with the modifiers of that moment). */
    private val heldKeys = HashMap<KeyStroke, KeyStroke>()

    fun bindEditor(reset: (() -> Unit)?) {
        resetEditor = reset
    }

    fun configure(autoSwitch: Boolean, toggleKey: LanguageToggleKey) {
        encoder.autoSwitch = autoSwitch
        encoder.toggleKey = toggleKey
    }

    val activeModifierMask: Int
        get() = _modifiers.value.entries.filter { it.value != LatchState.OFF }.fold(0) { m, e -> m or e.key.mask }

    fun cycleModifier(key: ModifierKey) = _modifiers.update { map ->
        val next = when (map.getValue(key)) {
            LatchState.OFF -> LatchState.ONCE
            LatchState.ONCE -> LatchState.LOCKED
            LatchState.LOCKED -> LatchState.OFF
        }
        map + (key to next)
    }

    fun clearModifiers() = _modifiers.update { map -> map.mapValues { LatchState.OFF } }

    private fun consumeOnceModifiers() = _modifiers.update { map ->
        map.mapValues { (_, v) -> if (v == LatchState.ONCE) LatchState.OFF else v }
    }

    /** Called with the full local editor text after every IME edit. */
    fun onEditorText(text: String) {
        val mask = activeModifierMask
        if (mask != 0) {
            // Shortcut mode: new characters are sent as physical keys with the latched modifiers.
            val prefix = TextMirror.commonPrefixLength(mirror.text, text)
            val strokes = text.substring(prefix).mapNotNull { encoder.physicalKey(it)?.withModifiers(mask) }
            if (strokes.isNotEmpty()) {
                mirror.endComposition()
                sender.type(strokes)
                consumeOnceModifiers()
            }
            resetLocalEditor()
            return
        }
        mirror.update(text)
        publish()
        if (text.length > MAX_EDITOR_LENGTH && !mirror.hostComposing) resetLocalEditor()
    }

    /** Sends a non-text key (arrow, Enter, F-key…) applying latched modifiers. */
    fun sendKey(stroke: KeyStroke) {
        sender.type(listOf(stroke.withModifiers(activeModifierMask)))
        consumeOnceModifiers()
        afterHostCursorChange()
    }

    /** Holds a key so the host auto-repeats it; pair with [keyUp]. */
    fun keyDown(stroke: KeyStroke) {
        val sent = stroke.withModifiers(activeModifierMask)
        heldKeys[stroke] = sent
        sender.keyDown(sent)
        afterHostCursorChange()
    }

    fun keyUp(stroke: KeyStroke) {
        val sent = heldKeys.remove(stroke) ?: return
        sender.keyUp(sent)
        consumeOnceModifiers()
    }

    fun onTouchpad(action: TouchpadAction) {
        when (action) {
            is TouchpadAction.Move -> sender.move(action.dx, action.dy)
            is TouchpadAction.Scroll -> sender.scroll(action.vertical, action.horizontal)
            is TouchpadAction.Click -> {
                withHeldModifiers { sender.click(action.button) }
                afterHostCursorChange()
            }
            is TouchpadAction.Press -> {
                val mask = activeModifierMask
                // Remember what was pressed so Release undoes exactly that, even if the
                // modifier latch changed during the drag.
                dragModifierMask = mask
                if (mask != 0) sender.keyDown(KeyStroke(0, mask))
                sender.press(action.button)
                afterHostCursorChange()
            }
            is TouchpadAction.Release -> {
                sender.release(action.button)
                if (dragModifierMask != 0) {
                    sender.keyUp(KeyStroke(0, dragModifierMask))
                    dragModifierMask = 0
                    consumeOnceModifiers()
                }
            }
        }
    }

    fun click(button: Int, count: Int = 1) {
        withHeldModifiers { sender.click(button, count) }
        afterHostCursorChange()
    }

    private inline fun withHeldModifiers(block: () -> Unit) {
        val mask = activeModifierMask
        if (mask == 0) return block()
        val stroke = KeyStroke(0, mask)
        sender.keyDown(stroke)
        block()
        sender.keyUp(stroke)
        consumeOnceModifiers()
    }

    /** Sends the 한/영 toggle and flips the assumed host language. */
    fun toggleLanguage() {
        mirror.endComposition()
        sender.type(listOf(encoder.toggleStroke()))
        publish()
    }

    /** Corrects the assumed host language without sending anything. */
    fun syncLanguage(language: HostLanguage) {
        encoder.hostLanguage = language
        mirror.endComposition()
        publish()
    }

    fun onHostLeds(leds: Int) {
        val caps = (leds and KeyboardLed.CAPS_LOCK) != 0
        encoder.capsLock = caps
        _capsLock.value = caps
    }

    /** The host disconnected or reconnected: its IME state is unknown again. */
    fun onHostReset() {
        sender.releaseAll()
        dragModifierMask = 0
        heldKeys.clear()
        clearModifiers()
        mirror.endComposition()
        resetLocalEditor()
    }

    fun clearInput() = resetLocalEditor()

    private fun afterHostCursorChange() {
        mirror.endComposition()
        resetLocalEditor()
    }

    private fun resetLocalEditor() {
        mirror.reset()
        publish()
        resetEditor?.invoke()
    }

    private fun publish() {
        _mirrored.value = mirror.text
        _hostLanguage.value = encoder.hostLanguage
    }

    companion object {
        const val MAX_EDITOR_LENGTH = 500
    }
}
