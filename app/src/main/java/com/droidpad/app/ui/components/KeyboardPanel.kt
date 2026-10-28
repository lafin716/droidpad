package com.droidpad.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.KeyboardTab
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.droidpad.app.R
import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier as Mod
import com.droidpad.app.input.InputController
import com.droidpad.app.input.LatchState
import com.droidpad.app.input.ModifierKey
import com.droidpad.app.input.keyboard.HostLanguage

/** Everything needed to type on the host: IME bridge, modifiers, special keys. */
@Composable
fun KeyboardPanel(
    controller: InputController,
    enabled: Boolean,
    mirrored: String,
    modifiers: Map<ModifierKey, LatchState>,
    hostLanguage: HostLanguage,
    capsLock: Boolean,
    unsupportedCount: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onInputViewReady: (HidInputView) -> Unit = {},
) {
    var inputView by remember { mutableStateOf<HidInputView?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InputPreview(
            text = mirrored,
            enabled = enabled,
            capsLock = capsLock,
            unsupportedCount = unsupportedCount,
            onTap = { inputView?.showKeyboard() },
            onClear = controller::clearInput,
            onHide = { inputView?.hideKeyboard() },
        ) {
            AndroidView(
                factory = { ctx ->
                    HidInputView(ctx).also {
                        it.controller = controller
                        inputView = it
                        onInputViewReady(it)
                    }
                },
                update = { it.controller = controller },
                onRelease = { it.controller = null },
                modifier = Modifier.size(1.dp),
            )
        }
        ModifierRow(modifiers, hostLanguage, enabled, controller)
        SpecialKeys(controller, enabled, compact)
    }
}

@Composable
private fun InputPreview(
    text: String,
    enabled: Boolean,
    capsLock: Boolean,
    unsupportedCount: Int,
    onTap: () -> Unit,
    onClear: () -> Unit,
    onHide: () -> Unit,
    editor: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, onClick = onTap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Icon(Icons.Outlined.Keyboard, null, tint = colors.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                val placeholder = text.isEmpty()
                Text(
                    if (placeholder) stringResource(if (enabled) R.string.keyboard_tap_to_type else R.string.keyboard_connect_first)
                    else text.takeLast(120).replace('\n', '↵'),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (placeholder) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.StartEllipsis,
                )
                val notes = buildList {
                    if (capsLock) add(stringResource(R.string.keyboard_caps_on))
                    if (unsupportedCount > 0) add(stringResource(R.string.keyboard_unsupported, unsupportedCount))
                }
                if (notes.isNotEmpty()) {
                    Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
                }
            }
            editor()
            if (text.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Outlined.Clear, stringResource(R.string.keyboard_clear)) }
            }
            IconButton(onClick = onHide) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.keyboard_hide)) }
        }
    }
}

@Composable
private fun ModifierRow(
    modifiers: Map<ModifierKey, LatchState>,
    hostLanguage: HostLanguage,
    enabled: Boolean,
    controller: InputController,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageChip(hostLanguage, enabled, controller)
        for (key in ModifierKey.entries) {
            val state = modifiers[key] ?: LatchState.OFF
            val label = stringResource(
                when (key) {
                    ModifierKey.CTRL -> R.string.key_ctrl
                    ModifierKey.SHIFT -> R.string.key_shift
                    ModifierKey.ALT -> R.string.key_alt
                    ModifierKey.META -> R.string.key_meta
                },
            )
            FilterChip(
                selected = state != LatchState.OFF,
                onClick = { controller.cycleModifier(key) },
                enabled = enabled,
                label = { Text(if (state == LatchState.LOCKED) "$label 🔒" else label) },
                colors = if (state == LatchState.LOCKED) {
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    FilterChipDefaults.filterChipColors()
                },
            )
        }
    }
}

@Composable
private fun LanguageChip(hostLanguage: HostLanguage, enabled: Boolean, controller: InputController) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { controller.toggleLanguage() },
            enabled = enabled,
            leadingIcon = { Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp)) },
            label = {
                Text(
                    if (hostLanguage == HostLanguage.KOREAN) stringResource(R.string.lang_korean_short)
                    else stringResource(R.string.lang_english_short),
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    stringResource(R.string.lang_sync),
                    Modifier.size(18.dp).clickable(enabled = enabled) { menu = true },
                )
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                stringResource(R.string.lang_sync_title),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lang_sync_english)) },
                onClick = { controller.syncLanguage(HostLanguage.ENGLISH); menu = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lang_sync_korean)) },
                onClick = { controller.syncLanguage(HostLanguage.KOREAN); menu = false },
            )
        }
    }
}

private data class KeySpec(
    val label: String,
    val stroke: KeyStroke,
    val icon: ImageVector? = null,
    val hold: Boolean = true,
)

@Composable
private fun SpecialKeys(controller: InputController, enabled: Boolean, compact: Boolean) {
    val keyHeight: Dp = if (compact) 44.dp else 52.dp
    val esc = KeySpec("Esc", KeyStroke(KeyCodes.ESCAPE))
    val tab = KeySpec("Tab", KeyStroke(KeyCodes.TAB), Icons.AutoMirrored.Outlined.KeyboardTab)
    val bksp = KeySpec(stringResource(R.string.key_backspace), KeyStroke(KeyCodes.BACKSPACE), Icons.AutoMirrored.Outlined.Backspace)
    val del = KeySpec("Del", KeyStroke(KeyCodes.DELETE))
    val enter = KeySpec(stringResource(R.string.key_enter), KeyStroke(KeyCodes.ENTER), Icons.AutoMirrored.Outlined.KeyboardReturn)
    val up = KeySpec(stringResource(R.string.key_up), KeyStroke(KeyCodes.UP), Icons.Outlined.ArrowUpward)
    val down = KeySpec(stringResource(R.string.key_down), KeyStroke(KeyCodes.DOWN), Icons.Outlined.ArrowDownward)
    val left = KeySpec(stringResource(R.string.key_left), KeyStroke(KeyCodes.LEFT), Icons.AutoMirrored.Outlined.ArrowBack)
    val right = KeySpec(stringResource(R.string.key_right), KeyStroke(KeyCodes.RIGHT), Icons.AutoMirrored.Outlined.ArrowForward)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyRow(listOf(esc, tab, del, bksp, enter), controller, enabled, keyHeight)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyRow(
                    listOf(KeySpec("Home", KeyStroke(KeyCodes.HOME)), KeySpec("PgUp", KeyStroke(KeyCodes.PAGE_UP))),
                    controller, enabled, keyHeight,
                )
                KeyRow(
                    listOf(KeySpec("End", KeyStroke(KeyCodes.END)), KeySpec("PgDn", KeyStroke(KeyCodes.PAGE_DOWN))),
                    controller, enabled, keyHeight,
                )
            }
            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    Key(up, controller, enabled, Modifier.weight(1f).height(keyHeight))
                    Spacer(Modifier.weight(1f))
                }
                KeyRow(listOf(left, down, right), controller, enabled, keyHeight)
            }
        }
        if (!compact) {
            val shortcuts = listOf(
                KeySpec(stringResource(R.string.shortcut_copy), KeyStroke(KeyCodes.letter('c'), Mod.LEFT_CTRL), hold = false),
                KeySpec(stringResource(R.string.shortcut_paste), KeyStroke(KeyCodes.letter('v'), Mod.LEFT_CTRL), hold = false),
                KeySpec(stringResource(R.string.shortcut_cut), KeyStroke(KeyCodes.letter('x'), Mod.LEFT_CTRL), hold = false),
                KeySpec(stringResource(R.string.shortcut_undo), KeyStroke(KeyCodes.letter('z'), Mod.LEFT_CTRL), hold = false),
                KeySpec(stringResource(R.string.shortcut_select_all), KeyStroke(KeyCodes.letter('a'), Mod.LEFT_CTRL), hold = false),
                KeySpec(stringResource(R.string.shortcut_switch_app), KeyStroke(KeyCodes.TAB, Mod.LEFT_ALT), hold = false),
                KeySpec(stringResource(R.string.shortcut_home), KeyStroke(KeyCodes.ENTER, Mod.LEFT_GUI), hold = false),
                KeySpec(stringResource(R.string.shortcut_back), KeyStroke(KeyCodes.ESCAPE), hold = false),
            )
            ScrollKeyRow(shortcuts, controller, enabled, keyHeight)
            ScrollKeyRow(
                (1..12).map { KeySpec("F$it", KeyStroke(KeyCodes.function(it))) },
                controller, enabled, keyHeight,
            )
        }
    }
}

@Composable
private fun KeyRow(keys: List<KeySpec>, controller: InputController, enabled: Boolean, height: Dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { Key(it, controller, enabled, Modifier.weight(1f).height(height)) }
    }
}

@Composable
private fun ScrollKeyRow(keys: List<KeySpec>, controller: InputController, enabled: Boolean, height: Dp) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        keys.forEach { Key(it, controller, enabled, Modifier.heightIn(min = height).height(height).width(76.dp)) }
    }
}

@Composable
private fun Key(spec: KeySpec, controller: InputController, enabled: Boolean, modifier: Modifier) {
    if (spec.hold) {
        KeyCap(spec, enabled, modifier, onDown = { controller.keyDown(spec.stroke) }, onUp = { controller.keyUp(spec.stroke) })
    } else {
        KeyCap(spec, enabled, modifier, onDown = { controller.sendKey(spec.stroke) }, onUp = {})
    }
}

@Composable
private fun KeyCap(spec: KeySpec, enabled: Boolean, modifier: Modifier, onDown: () -> Unit, onUp: () -> Unit) {
    HoldButton(
        label = if (spec.icon == null) spec.label else "",
        enabled = enabled,
        onDown = onDown,
        onUp = onUp,
        modifier = modifier.semantics { contentDescription = spec.label },
        content = spec.icon?.let { icon -> { Icon(icon, null) } },
        monospace = spec.icon == null,
    )
}
