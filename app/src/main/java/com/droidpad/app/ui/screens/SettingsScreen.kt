package com.droidpad.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidpad.app.BuildConfig
import com.droidpad.app.MainViewModel
import com.droidpad.app.R
import com.droidpad.app.data.AppSettings
import com.droidpad.app.data.KeyboardInputMode
import com.droidpad.app.data.LayoutMode
import com.droidpad.app.data.ThemeMode
import com.droidpad.app.input.keyboard.LanguageToggleKey
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onEditLayout: () -> Unit, onOpenDeskLab: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val update = vm::updateSettings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Section(stringResource(R.string.settings_touchpad)) {
                SliderRow(
                    stringResource(R.string.settings_pointer_speed), s.pointerSpeed,
                    AppSettings.MIN_POINTER_SPEED..AppSettings.MAX_POINTER_SPEED,
                ) { v -> update { it.copy(pointerSpeed = v) } }
                SliderRow(
                    stringResource(R.string.settings_scroll_speed), s.scrollSpeed,
                    AppSettings.MIN_SCROLL_SPEED..AppSettings.MAX_SCROLL_SPEED,
                ) { v -> update { it.copy(scrollSpeed = v) } }
                SwitchRow(stringResource(R.string.settings_acceleration), stringResource(R.string.settings_acceleration_desc), s.acceleration) { v ->
                    update { it.copy(acceleration = v) }
                }
                SwitchRow(stringResource(R.string.settings_natural_scroll), stringResource(R.string.settings_natural_scroll_desc), s.naturalScroll) { v ->
                    update { it.copy(naturalScroll = v) }
                }
                SwitchRow(stringResource(R.string.settings_tap_to_click), null, s.tapToClick) { v -> update { it.copy(tapToClick = v) } }
                SwitchRow(stringResource(R.string.settings_haptics), null, s.haptics) { v -> update { it.copy(haptics = v) } }
            }
            Section(stringResource(R.string.air_mouse_title)) {
                SliderRow(
                    stringResource(R.string.settings_air_speed), s.airMouseSpeed,
                    AppSettings.MIN_POINTER_SPEED..AppSettings.MAX_POINTER_SPEED,
                ) { v -> update { it.copy(airMouseSpeed = v) } }
                SwitchRow(stringResource(R.string.settings_air_hold), stringResource(R.string.settings_air_hold_desc), s.airMouseHoldToMove) { v ->
                    update { it.copy(airMouseHoldToMove = v) }
                }
            }
            Section(stringResource(R.string.settings_layout)) {
                Text(stringResource(R.string.settings_layout_mode), style = MaterialTheme.typography.bodyLarge)
                Segmented(
                    LayoutMode.entries, s.layoutMode,
                    label = { if (it == LayoutMode.PRESET) R.string.layout_preset else R.string.layout_custom },
                ) { v -> update { it.copy(layoutMode = v) } }
                SwitchRow(stringResource(R.string.settings_swap_panes), stringResource(R.string.settings_swap_panes_desc), s.swapPanes) { v ->
                    update { it.copy(swapPanes = v) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.settings_edit_layout), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_edit_layout_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = onEditLayout) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.editor_title), Modifier.padding(start = 8.dp))
                    }
                }
            }
            Section(stringResource(R.string.settings_keyboard)) {
                Text(stringResource(R.string.settings_input_mode), style = MaterialTheme.typography.bodyLarge)
                Segmented(
                    KeyboardInputMode.entries, s.keyboardInputMode,
                    label = { if (it == KeyboardInputMode.SYSTEM_IME) R.string.input_mode_system else R.string.input_mode_virtual },
                ) { v -> update { it.copy(keyboardInputMode = v) } }
                SwitchRow(
                    stringResource(R.string.settings_auto_lang), stringResource(R.string.settings_auto_lang_desc), s.autoLanguageSwitch,
                ) { v -> update { it.copy(autoLanguageSwitch = v) } }
                Text(stringResource(R.string.settings_toggle_key), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_toggle_key_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageToggleKey.entries.forEach { key ->
                        FilterChip(
                            selected = s.languageToggleKey == key,
                            onClick = { update { it.copy(languageToggleKey = key) } },
                            label = { Text(toggleKeyLabel(key)) },
                        )
                    }
                }
            }
            Section(stringResource(R.string.settings_connection)) {
                SwitchRow(stringResource(R.string.settings_auto_reconnect), stringResource(R.string.settings_auto_reconnect_desc), s.autoReconnect) { v ->
                    update { it.copy(autoReconnect = v) }
                }
                SwitchRow(stringResource(R.string.settings_keep_screen_on), null, s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
            }
            Section(stringResource(R.string.settings_appearance)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = s.themeMode == mode,
                            onClick = { update { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            label = {
                                Text(
                                    stringResource(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> R.string.theme_system
                                            ThemeMode.LIGHT -> R.string.theme_light
                                            ThemeMode.DARK -> R.string.theme_dark
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
                SwitchRow(stringResource(R.string.settings_dynamic_color), null, s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }
            }
            Section(stringResource(R.string.settings_labs)) {
                Text(
                    stringResource(R.string.settings_labs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(stringResource(R.string.lab_desk_title), stringResource(R.string.settings_lab_desk_desc), s.labDeskMouse) { v ->
                    update { it.copy(labDeskMouse = v) }
                }
                if (s.labDeskMouse) {
                    FilledTonalButton(onClick = onOpenDeskLab, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Science, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.settings_lab_desk_open), Modifier.padding(start = 8.dp))
                    }
                }
            }
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun toggleKeyLabel(key: LanguageToggleKey): String = stringResource(
    when (key) {
        LanguageToggleKey.SHIFT_SPACE -> R.string.toggle_shift_space
        LanguageToggleKey.HANGUL_KEY -> R.string.toggle_hangul
        LanguageToggleKey.RIGHT_ALT -> R.string.toggle_right_alt
        LanguageToggleKey.CTRL_SPACE -> R.string.toggle_ctrl_space
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> Int, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(stringResource(label(option)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.1f×", value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = { onChange(Math.round(it * 10) / 10f) }, valueRange = range, steps = 26)
    }
}

@Composable
private fun SwitchRow(label: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
