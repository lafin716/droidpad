package com.droidpad.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.KeyboardTab
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droidpad.app.R
import com.droidpad.app.data.KeyboardInputMode
import com.droidpad.app.data.LayoutItem
import com.droidpad.app.data.WidgetType
import com.droidpad.app.hid.KeyCodes
import com.droidpad.app.hid.KeyStroke
import com.droidpad.app.hid.Modifier as Mod
import com.droidpad.app.input.InputController
import com.droidpad.app.input.LatchState
import com.droidpad.app.input.ModifierKey
import com.droidpad.app.input.keyboard.HostLanguage
import com.droidpad.app.input.touchpad.TouchpadAction
import com.droidpad.app.input.touchpad.TouchpadConfig
import com.droidpad.app.hid.MouseButton

/** Minimum size of one grid cell: the size of a single key. */
val GRID_CELL: Dp = 56.dp

/** Gap drawn around each widget inside its cells. */
val GRID_GAP: Dp = 3.dp

/** Grid geometry for the available space; cells are stretched to fill it exactly. */
data class GridMetrics(val cols: Int, val rows: Int, val cellWidth: Dp, val cellHeight: Dp)

/** Positions and sizes a widget over [item]'s cells in [grid]. */
fun Modifier.gridItem(grid: GridMetrics, item: LayoutItem): Modifier = this
    .offset(grid.cellWidth * item.x, grid.cellHeight * item.y)
    .size(grid.cellWidth * item.w, grid.cellHeight * item.h)
    .padding(GRID_GAP)

/** Measures the space, splits it into key-sized cells and hands the geometry to [content]. */
@Composable
fun ControlGrid(modifier: Modifier = Modifier, content: @Composable (GridMetrics) -> Unit) {
    BoxWithConstraints(modifier) {
        val cols = (maxWidth / GRID_CELL).toInt().coerceAtLeast(1)
        val rows = (maxHeight / GRID_CELL).toInt().coerceAtLeast(1)
        val metrics = GridMetrics(cols, rows, maxWidth / cols, maxHeight / rows)
        Box(Modifier.fillMaxSize()) {
            content(metrics)
        }
    }
}

/** Live state the grid widgets need to send input. */
data class GridWidgetState(
    val controller: InputController,
    val enabled: Boolean,
    val mirrored: String,
    val modifiers: Map<ModifierKey, LatchState>,
    val hostLanguage: HostLanguage,
    val capsLock: Boolean,
    val unsupportedCount: Int,
    val touchpadConfig: TouchpadConfig,
    val haptics: Boolean,
    val inputMode: KeyboardInputMode,
    val onOpenVirtualKeyboard: () -> Unit,
)

/** Interactive widget for one layout cell group. */
@Composable
fun GridWidget(item: LayoutItem, state: GridWidgetState, modifier: Modifier = Modifier) {
    val c = state.controller
    when (val type = item.type) {
        WidgetType.TOUCHPAD -> TouchpadSurface(
            config = state.touchpadConfig,
            haptics = state.haptics,
            enabled = state.enabled,
            onAction = c::onTouchpad,
            modifier = modifier,
            showHint = item.w >= 3 && item.h >= 3,
        )
        WidgetType.INPUT_BAR -> TextInputBar(
            controller = c,
            enabled = state.enabled,
            mirrored = state.mirrored,
            capsLock = state.capsLock,
            unsupportedCount = state.unsupportedCount,
            inputMode = state.inputMode,
            onOpenVirtualKeyboard = state.onOpenVirtualKeyboard,
            modifier = modifier,
            singleLine = item.h == 1,
        )
        WidgetType.MOUSE_LEFT, WidgetType.MOUSE_RIGHT, WidgetType.MOUSE_MIDDLE -> {
            val button = when (type) {
                WidgetType.MOUSE_LEFT -> MouseButton.LEFT
                WidgetType.MOUSE_RIGHT -> MouseButton.RIGHT
                else -> MouseButton.MIDDLE
            }
            HoldButton(
                label = widgetLabel(type),
                enabled = state.enabled,
                onDown = { c.onTouchpad(TouchpadAction.Press(button)) },
                onUp = { c.onTouchpad(TouchpadAction.Release(button)) },
                modifier = modifier,
            )
        }
        WidgetType.CTRL, WidgetType.SHIFT, WidgetType.ALT, WidgetType.META -> {
            val key = modifierKeyFor(type)
            ModifierKeyButton(widgetLabel(type), state.modifiers[key] ?: LatchState.OFF, state.enabled, modifier) {
                c.cycleModifier(key)
            }
        }
        WidgetType.LANGUAGE -> LanguageKey(state.hostLanguage, state.enabled, c, modifier)
        else -> keySpecFor(type)?.let { Key(it, c, state.enabled, modifier) }
    }
}

internal fun modifierKeyFor(type: WidgetType): ModifierKey = when (type) {
    WidgetType.CTRL -> ModifierKey.CTRL
    WidgetType.SHIFT -> ModifierKey.SHIFT
    WidgetType.ALT -> ModifierKey.ALT
    else -> ModifierKey.META
}

/** A latching modifier key: tap cycles off → once → locked. */
@Composable
internal fun ModifierKeyButton(
    label: String,
    state: LatchState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = when {
            !enabled -> colors.surfaceContainerHighest.copy(alpha = 0.5f)
            state == LatchState.LOCKED -> colors.primary
            state == LatchState.ONCE -> colors.tertiaryContainer
            else -> colors.secondaryContainer
        },
        contentColor = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            state == LatchState.LOCKED -> colors.onPrimary
            state == LatchState.ONCE -> colors.onTertiaryContainer
            else -> colors.onSecondaryContainer
        },
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (state == LatchState.LOCKED) "$label 🔒" else label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/** 한/영 key: tap toggles, long-press tells DroidPad which language the host is using. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LanguageKey(hostLanguage: HostLanguage, enabled: Boolean, controller: InputController, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (enabled) colors.tertiaryContainer else colors.surfaceContainerHighest.copy(alpha = 0.5f),
            contentColor = if (enabled) colors.onTertiaryContainer else colors.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.fillMaxSize().combinedClickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.widget_language),
                onLongClickLabel = stringResource(R.string.lang_sync),
                onLongClick = { menu = true },
                onClick = controller::toggleLanguage,
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (hostLanguage == HostLanguage.KOREAN) R.string.lang_korean_short else R.string.lang_english_short),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
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

/** Non-interactive stand-in used by the layout editor. */
@Composable
fun GridWidgetPreview(type: WidgetType, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val big = type == WidgetType.TOUCHPAD || type == WidgetType.INPUT_BAR
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            highlighted -> colors.primaryContainer
            big -> colors.surfaceContainerHigh
            else -> colors.secondaryContainer
        },
        contentColor = if (highlighted) colors.onPrimaryContainer else colors.onSecondaryContainer,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(4.dp)) {
            Text(
                widgetLabel(type),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = if (big) null else FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Human-readable name of a widget, shown on keys and in the editor. */
@Composable
fun widgetLabel(type: WidgetType): String {
    type.functionNumber?.let { return "F$it" }
    return when (type) {
        WidgetType.TOUCHPAD -> stringResource(R.string.widget_touchpad)
        WidgetType.INPUT_BAR -> stringResource(R.string.widget_input_bar)
        WidgetType.MOUSE_LEFT -> stringResource(R.string.mouse_left)
        WidgetType.MOUSE_RIGHT -> stringResource(R.string.mouse_right)
        WidgetType.MOUSE_MIDDLE -> stringResource(R.string.widget_mouse_middle)
        WidgetType.LANGUAGE -> stringResource(R.string.widget_language)
        WidgetType.CTRL -> stringResource(R.string.key_ctrl)
        WidgetType.SHIFT -> stringResource(R.string.key_shift)
        WidgetType.ALT -> stringResource(R.string.key_alt)
        WidgetType.META -> stringResource(R.string.key_meta)
        WidgetType.ESC -> "Esc"
        WidgetType.TAB -> "Tab"
        WidgetType.BACKSPACE -> "⌫"
        WidgetType.DELETE -> "Del"
        WidgetType.ENTER -> stringResource(R.string.key_enter)
        WidgetType.SPACE -> "Space"
        WidgetType.UP -> "↑"
        WidgetType.DOWN -> "↓"
        WidgetType.LEFT -> "←"
        WidgetType.RIGHT -> "→"
        WidgetType.HOME -> "Home"
        WidgetType.END -> "End"
        WidgetType.PAGE_UP -> "PgUp"
        WidgetType.PAGE_DOWN -> "PgDn"
        WidgetType.INSERT -> "Ins"
        WidgetType.COPY -> stringResource(R.string.shortcut_copy)
        WidgetType.PASTE -> stringResource(R.string.shortcut_paste)
        WidgetType.CUT -> stringResource(R.string.shortcut_cut)
        WidgetType.UNDO -> stringResource(R.string.shortcut_undo)
        WidgetType.SELECT_ALL -> stringResource(R.string.shortcut_select_all)
        WidgetType.SWITCH_APP -> stringResource(R.string.shortcut_switch_app)
        WidgetType.HOST_HOME -> stringResource(R.string.shortcut_home)
        WidgetType.BACK -> stringResource(R.string.shortcut_back)
        else -> type.name
    }
}

/** Key behaviour for plain key widgets; null for widgets that are not simple keys. */
@Composable
internal fun keySpecFor(type: WidgetType): KeySpec? {
    val label = widgetLabel(type)
    type.functionNumber?.let { return KeySpec(label, KeyStroke(KeyCodes.function(it))) }
    return when (type) {
        WidgetType.ESC -> KeySpec(label, KeyStroke(KeyCodes.ESCAPE))
        WidgetType.TAB -> KeySpec(label, KeyStroke(KeyCodes.TAB), Icons.AutoMirrored.Outlined.KeyboardTab)
        WidgetType.BACKSPACE -> KeySpec(label, KeyStroke(KeyCodes.BACKSPACE), Icons.AutoMirrored.Outlined.Backspace)
        WidgetType.DELETE -> KeySpec(label, KeyStroke(KeyCodes.DELETE))
        WidgetType.ENTER -> KeySpec(label, KeyStroke(KeyCodes.ENTER), Icons.AutoMirrored.Outlined.KeyboardReturn)
        WidgetType.SPACE -> KeySpec(label, KeyStroke(KeyCodes.SPACE))
        WidgetType.UP -> KeySpec(label, KeyStroke(KeyCodes.UP), Icons.Outlined.ArrowUpward)
        WidgetType.DOWN -> KeySpec(label, KeyStroke(KeyCodes.DOWN), Icons.Outlined.ArrowDownward)
        WidgetType.LEFT -> KeySpec(label, KeyStroke(KeyCodes.LEFT), Icons.AutoMirrored.Outlined.ArrowBack)
        WidgetType.RIGHT -> KeySpec(label, KeyStroke(KeyCodes.RIGHT), Icons.AutoMirrored.Outlined.ArrowForward)
        WidgetType.HOME -> KeySpec(label, KeyStroke(KeyCodes.HOME))
        WidgetType.END -> KeySpec(label, KeyStroke(KeyCodes.END))
        WidgetType.PAGE_UP -> KeySpec(label, KeyStroke(KeyCodes.PAGE_UP))
        WidgetType.PAGE_DOWN -> KeySpec(label, KeyStroke(KeyCodes.PAGE_DOWN))
        WidgetType.INSERT -> KeySpec(label, KeyStroke(KeyCodes.INSERT))
        WidgetType.COPY -> KeySpec(label, KeyStroke(KeyCodes.letter('c'), Mod.LEFT_CTRL), hold = false)
        WidgetType.PASTE -> KeySpec(label, KeyStroke(KeyCodes.letter('v'), Mod.LEFT_CTRL), hold = false)
        WidgetType.CUT -> KeySpec(label, KeyStroke(KeyCodes.letter('x'), Mod.LEFT_CTRL), hold = false)
        WidgetType.UNDO -> KeySpec(label, KeyStroke(KeyCodes.letter('z'), Mod.LEFT_CTRL), hold = false)
        WidgetType.SELECT_ALL -> KeySpec(label, KeyStroke(KeyCodes.letter('a'), Mod.LEFT_CTRL), hold = false)
        WidgetType.SWITCH_APP -> KeySpec(label, KeyStroke(KeyCodes.TAB, Mod.LEFT_ALT), hold = false)
        WidgetType.HOST_HOME -> KeySpec(label, KeyStroke(KeyCodes.ENTER, Mod.LEFT_GUI), hold = false)
        WidgetType.BACK -> KeySpec(label, KeyStroke(KeyCodes.ESCAPE), hold = false)
        else -> null
    }
}
