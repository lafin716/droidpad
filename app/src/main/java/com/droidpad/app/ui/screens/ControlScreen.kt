package com.droidpad.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidpad.app.MainViewModel
import com.droidpad.app.R
import com.droidpad.app.data.AppSettings
import com.droidpad.app.hid.HidState
import com.droidpad.app.hid.HidStatus
import com.droidpad.app.input.touchpad.TouchpadConfig
import com.droidpad.app.input.touchpad.TouchpadAction
import com.droidpad.app.ui.components.KeyboardPanel
import com.droidpad.app.ui.components.MouseButtons
import com.droidpad.app.ui.components.TouchpadSurface

private enum class ControlMode { TOUCHPAD, KEYBOARD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(
    vm: MainViewModel,
    wide: Boolean,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hid by vm.hid.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val connected = hid.status == HidStatus.CONNECTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    ConnectionChip(hid, onOpenDevices)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        val contentModifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
        if (wide) {
            Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TouchpadPane(vm, settings, connected, Modifier.weight(1.15f).fillMaxSize())
                KeyboardPane(vm, connected, compact = false, Modifier.weight(1f).fillMaxSize())
            }
        } else {
            var mode by rememberSaveable { mutableIntStateOf(ControlMode.TOUCHPAD.ordinal) }
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            Column(contentModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!imeVisible) {
                    ModeSwitch(ControlMode.entries[mode]) { mode = it.ordinal }
                }
                when (ControlMode.entries[mode]) {
                    ControlMode.TOUCHPAD -> TouchpadPane(vm, settings, connected, Modifier.weight(1f).fillMaxWidth())
                    ControlMode.KEYBOARD -> KeyboardPane(vm, connected, compact = imeVisible, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: ControlMode, onSelect: (ControlMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ControlMode.entries.forEachIndexed { index, m ->
            val (label, icon) = when (m) {
                ControlMode.TOUCHPAD -> stringResource(R.string.mode_touchpad) to Icons.Outlined.TouchApp
                ControlMode.KEYBOARD -> stringResource(R.string.mode_keyboard) to Icons.Outlined.Keyboard
            }
            SegmentedButton(
                selected = mode == m,
                onClick = { onSelect(m) },
                shape = SegmentedButtonDefaults.itemShape(index, ControlMode.entries.size),
                icon = { SegmentedButtonDefaults.Icon(mode == m) { Icon(icon, null, Modifier.size(18.dp)) } },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun TouchpadPane(vm: MainViewModel, settings: AppSettings, connected: Boolean, modifier: Modifier) {
    val config = remember(settings) {
        TouchpadConfig(
            pointerSpeed = settings.pointerSpeed,
            acceleration = settings.acceleration,
            scrollSpeed = settings.scrollSpeed,
            naturalScroll = settings.naturalScroll,
            tapToClick = settings.tapToClick,
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TouchpadSurface(
            config = config,
            haptics = settings.haptics,
            enabled = connected,
            onAction = vm.input::onTouchpad,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        MouseButtons(
            enabled = connected,
            onPress = { vm.input.onTouchpad(TouchpadAction.Press(it)) },
            onRelease = { vm.input.onTouchpad(TouchpadAction.Release(it)) },
        )
    }
}

@Composable
private fun KeyboardPane(vm: MainViewModel, connected: Boolean, compact: Boolean, modifier: Modifier) {
    val mirrored by vm.mirrored.collectAsStateWithLifecycle()
    val modifiers by vm.modifiers.collectAsStateWithLifecycle()
    val language by vm.hostLanguage.collectAsStateWithLifecycle()
    val caps by vm.capsLock.collectAsStateWithLifecycle()
    val unsupported by vm.unsupportedCount.collectAsStateWithLifecycle()
    KeyboardPanel(
        controller = vm.input,
        enabled = connected,
        mirrored = mirrored,
        modifiers = modifiers,
        hostLanguage = language,
        capsLock = caps,
        unsupportedCount = unsupported,
        compact = compact,
        modifier = modifier.verticalScroll(rememberScrollState()),
    )
}

@Composable
private fun ConnectionChip(hid: HidState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (icon: ImageVector, label: String) = when (hid.status) {
        HidStatus.CONNECTED -> Icons.Outlined.BluetoothConnected to (hid.host?.name ?: stringResource(R.string.status_connected))
        HidStatus.CONNECTING -> Icons.Outlined.Bluetooth to stringResource(R.string.status_connecting)
        HidStatus.READY -> Icons.Outlined.Bluetooth to
            if (hid.reconnectAttempt > 0) stringResource(R.string.status_reconnecting, hid.reconnectAttempt)
            else stringResource(R.string.status_ready_short)
        else -> Icons.Outlined.BluetoothDisabled to statusLabel(hid.status)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp)) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        colors = if (hid.status == HidStatus.CONNECTED) {
            AssistChipDefaults.assistChipColors(
                containerColor = colors.primaryContainer,
                labelColor = colors.onPrimaryContainer,
                leadingIconContentColor = colors.onPrimaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
        modifier = Modifier.padding(end = 4.dp),
    )
}

@Composable
fun statusLabel(status: HidStatus): String = stringResource(
    when (status) {
        HidStatus.INITIALIZING -> R.string.status_initializing
        HidStatus.NO_BLUETOOTH -> R.string.status_no_bluetooth
        HidStatus.PERMISSION_REQUIRED -> R.string.status_permission
        HidStatus.BLUETOOTH_OFF -> R.string.status_bt_off
        HidStatus.UNSUPPORTED -> R.string.status_unsupported
        HidStatus.REGISTERING -> R.string.status_registering
        HidStatus.READY -> R.string.status_ready
        HidStatus.CONNECTING -> R.string.status_connecting
        HidStatus.CONNECTED -> R.string.status_connected
        HidStatus.DISCONNECTING -> R.string.status_disconnecting
        HidStatus.ERROR -> R.string.status_error
    },
)
