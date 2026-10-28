package com.droidpad.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droidpad.app.R
import com.droidpad.app.data.WidgetType
import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.input.InputController
import com.droidpad.app.input.LatchState
import com.droidpad.app.input.ModifierKey
import com.droidpad.app.input.keyboard.HostLanguage

private sealed interface VKey {
    val weight: Float

    /** A physical key. [base]/[shifted] are the printed labels; letters also get a Hangul label. */
    data class Stroke(
        val usage: Int,
        val base: String,
        val shifted: String? = null,
        val icon: ImageVector? = null,
        override val weight: Float = 1f,
        val letter: Boolean = false,
    ) : VKey

    data class Latch(val key: ModifierKey, override val weight: Float = 1.25f) : VKey
    data class Language(override val weight: Float = 1.25f) : VKey
    data class Close(override val weight: Float = 1.25f) : VKey
}

private fun letters(s: String): List<VKey> = s.map { VKey.Stroke(KeyCodes.letter(it), it.toString(), letter = true) }

private fun symbol(usage: Int, base: String, shifted: String) = VKey.Stroke(usage, base, shifted)

private val DIGITS = "1234567890"
private val DIGIT_SHIFTED = "!@#$%^&*()"

private val ROWS: List<List<VKey>> = listOf(
    listOf(VKey.Stroke(KeyCodes.ESCAPE, "Esc")) +
        (1..12).map { VKey.Stroke(KeyCodes.function(it), "F$it") } +
        VKey.Stroke(KeyCodes.DELETE, "Del"),
    listOf(symbol(KeyCodes.GRAVE, "`", "~")) +
        DIGITS.mapIndexed { i, d -> symbol(KeyCodes.NUM_1 + i, d.toString(), DIGIT_SHIFTED[i].toString()) } +
        listOf(
            symbol(KeyCodes.MINUS, "-", "_"),
            symbol(KeyCodes.EQUAL, "=", "+"),
            VKey.Stroke(KeyCodes.BACKSPACE, "Bksp", icon = Icons.AutoMirrored.Outlined.Backspace, weight = 1.75f),
        ),
    listOf(VKey.Stroke(KeyCodes.TAB, "Tab", weight = 1.5f)) + letters("qwertyuiop") + listOf(
        symbol(KeyCodes.LEFT_BRACKET, "[", "{"),
        symbol(KeyCodes.RIGHT_BRACKET, "]", "}"),
        symbol(KeyCodes.BACKSLASH, "\\", "|").copy(weight = 1.25f),
    ),
    listOf(VKey.Stroke(KeyCodes.CAPS_LOCK, "Caps", weight = 1.75f)) + letters("asdfghjkl") + listOf(
        symbol(KeyCodes.SEMICOLON, ";", ":"),
        symbol(KeyCodes.APOSTROPHE, "'", "\""),
        VKey.Stroke(KeyCodes.ENTER, "Enter", icon = Icons.AutoMirrored.Outlined.KeyboardReturn, weight = 2f),
    ),
    listOf(VKey.Latch(ModifierKey.SHIFT, weight = 2.25f)) + letters("zxcvbnm") + listOf(
        symbol(KeyCodes.COMMA, ",", "<"),
        symbol(KeyCodes.PERIOD, ".", ">"),
        symbol(KeyCodes.SLASH, "/", "?"),
        VKey.Latch(ModifierKey.SHIFT, weight = 2.25f),
    ),
    listOf(
        VKey.Latch(ModifierKey.CTRL),
        VKey.Latch(ModifierKey.META),
        VKey.Latch(ModifierKey.ALT),
        VKey.Language(),
        VKey.Stroke(KeyCodes.SPACE, "Space", weight = 4.5f),
        VKey.Stroke(KeyCodes.LEFT, "←", icon = Icons.AutoMirrored.Outlined.ArrowBack),
        VKey.Stroke(KeyCodes.UP, "↑", icon = Icons.Outlined.ArrowUpward),
        VKey.Stroke(KeyCodes.DOWN, "↓", icon = Icons.Outlined.ArrowDownward),
        VKey.Stroke(KeyCodes.RIGHT, "→", icon = Icons.AutoMirrored.Outlined.ArrowForward),
        VKey.Close(),
    ),
)

/** Standard 2-beolsik (두벌식) jamo printed on each letter key; uppercase = Shift. */
private val HANGUL_LABELS = mapOf(
    'q' to "ㅂ", 'w' to "ㅈ", 'e' to "ㄷ", 'r' to "ㄱ", 't' to "ㅅ", 'y' to "ㅛ", 'u' to "ㅕ", 'i' to "ㅑ", 'o' to "ㅐ", 'p' to "ㅔ",
    'a' to "ㅁ", 's' to "ㄴ", 'd' to "ㅇ", 'f' to "ㄹ", 'g' to "ㅎ", 'h' to "ㅗ", 'j' to "ㅓ", 'k' to "ㅏ", 'l' to "ㅣ",
    'z' to "ㅋ", 'x' to "ㅌ", 'c' to "ㅊ", 'v' to "ㅍ", 'b' to "ㅠ", 'n' to "ㅜ", 'm' to "ㅡ",
    'Q' to "ㅃ", 'W' to "ㅉ", 'E' to "ㄸ", 'R' to "ㄲ", 'T' to "ㅆ", 'O' to "ㅒ", 'P' to "ㅖ",
)

/**
 * Full-screen QWERTY keyboard that sends physical key positions straight to the host. Labels
 * follow the host's language (Hangul jamo when it is typing Korean) and the Shift/Caps state.
 */
@Composable
fun VirtualKeyboard(
    controller: InputController,
    enabled: Boolean,
    modifiers: Map<ModifierKey, LatchState>,
    hostLanguage: HostLanguage,
    capsLock: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val shift = (modifiers[ModifierKey.SHIFT] ?: LatchState.OFF) != LatchState.OFF
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ROWS.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { key -> VirtualKey(key, controller, enabled, modifiers, hostLanguage, shift, capsLock, onClose) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.VirtualKey(
    key: VKey,
    controller: InputController,
    enabled: Boolean,
    modifiers: Map<ModifierKey, LatchState>,
    hostLanguage: HostLanguage,
    shift: Boolean,
    capsLock: Boolean,
    onClose: () -> Unit,
) {
    val cell = Modifier.weight(key.weight).fillMaxHeight()
    when (key) {
        is VKey.Stroke -> {
            val stroke = KeyStroke(key.usage)
            val label = strokeLabel(key, hostLanguage, shift, capsLock)
            HoldButton(
                label = label,
                enabled = enabled,
                onDown = { controller.keyDown(stroke) },
                onUp = { controller.keyUp(stroke) },
                modifier = cell,
                content = key.icon?.let { icon -> { Icon(icon, key.base) } },
            )
        }
        is VKey.Latch -> ModifierKeyButton(
            widgetLabel(
                when (key.key) {
                    ModifierKey.CTRL -> WidgetType.CTRL
                    ModifierKey.SHIFT -> WidgetType.SHIFT
                    ModifierKey.ALT -> WidgetType.ALT
                    ModifierKey.META -> WidgetType.META
                },
            ),
            modifiers[key.key] ?: LatchState.OFF,
            enabled,
            cell,
        ) { controller.cycleModifier(key.key) }
        is VKey.Language -> LanguageKey(hostLanguage, enabled, controller, cell)
        is VKey.Close -> HoldButton(
            label = "",
            enabled = true,
            onDown = {},
            onUp = onClose,
            modifier = cell,
            content = { Icon(Icons.Outlined.KeyboardHide, stringResource(R.string.virtual_keyboard_close)) },
        )
    }
}

private fun strokeLabel(key: VKey.Stroke, hostLanguage: HostLanguage, shift: Boolean, capsLock: Boolean): String {
    if (key.letter) {
        val c = key.base[0]
        if (hostLanguage == HostLanguage.KOREAN) {
            return (if (shift) HANGUL_LABELS[c.uppercaseChar()] else null) ?: HANGUL_LABELS.getValue(c)
        }
        return if (shift != capsLock) c.uppercase() else c.toString()
    }
    return if (shift && key.shifted != null) key.shifted else key.base
}
