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
import androidx.compose.material.icons.outlined.ControlCamera
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import com.droidpad.app.data.CustomLayout
import com.droidpad.app.data.KeyboardInputMode
import com.droidpad.app.data.LayoutMode
import com.droidpad.app.ui.components.AirMouse
import com.droidpad.app.ui.components.ControlGrid
import com.droidpad.app.ui.components.GRID_GAP
import com.droidpad.app.ui.components.GridWidget
import com.droidpad.app.ui.components.GridWidgetState
import com.droidpad.app.ui.components.gridItem
import com.droidpad.app.ui.components.VirtualKeyboard
import com.droidpad.app.ui.components.formatRemaining
import com.droidpad.app.ui.components.rememberDiscoverableRemaining

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
    var virtualOpen by rememberSaveable { mutableStateOf(false) }
    val openVirtual = { virtualOpen = true }
    var airOpen by rememberSaveable { mutableStateOf(false) }
    val openAir = { airOpen = true }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        ConnectionChip(hid, onOpenDevices)
                        IconButton(onClick = openAir) {
                            Icon(Icons.Outlined.ControlCamera, stringResource(R.string.air_mouse_title))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
        ) { padding ->
            if (settings.layoutMode == LayoutMode.CUSTOM) {
                CustomControls(vm, settings, connected, wide, openVirtual, openAir, Modifier.controlGridArea(padding))
            } else {
                PresetControls(vm, settings, connected, wide, openVirtual, padding)
            }
        }
        if (virtualOpen) {
            val modifiers by vm.modifiers.collectAsStateWithLifecycle()
            val language by vm.hostLanguage.collectAsStateWithLifecycle()
            val caps by vm.capsLock.collectAsStateWithLifecycle()
            VirtualKeyboard(
                controller = vm.input,
                enabled = connected,
                modifiers = modifiers,
                hostLanguage = language,
                capsLock = caps,
                onClose = { virtualOpen = false },
            )
        }
        if (airOpen) {
            AirMouse(
                enabled = connected,
                speed = settings.airMouseSpeed,
                holdToMove = settings.airMouseHoldToMove,
                scrollSpeed = settings.scrollSpeed,
                naturalScroll = settings.naturalScroll,
                onAction = vm.input::onTouchpad,
                onClose = { airOpen = false },
            )
        }
    }
}

/**
 * Area used by the custom grid. Shared with the layout editor so both see the same grid size;
 * the outer padding is reduced by [GRID_GAP] so widget edges line up with the preset layout.
 */
fun Modifier.controlGridArea(padding: PaddingValues): Modifier = this
    .padding(padding)
    .fillMaxSize()
    .padding(horizontal = 16.dp - GRID_GAP)
    .padding(bottom = 12.dp - GRID_GAP)

@Composable
private fun PresetControls(
    vm: MainViewModel,
    settings: AppSettings,
    connected: Boolean,
    wide: Boolean,
    onOpenVirtualKeyboard: () -> Unit,
    padding: PaddingValues,
) {
    val contentModifier = Modifier
        .padding(padding)
        .fillMaxSize()
        .imePadding()
        .padding(horizontal = 16.dp)
        .padding(bottom = 12.dp)
    val inputMode = settings.keyboardInputMode
    if (wide) {
        Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val touchpad = @Composable { TouchpadPane(vm, settings, connected, Modifier.weight(1.15f).fillMaxSize()) }
            val keyboard = @Composable {
                KeyboardPane(vm, connected, compact = false, inputMode, onOpenVirtualKeyboard, Modifier.weight(1f).fillMaxSize())
            }
            if (settings.swapPanes) {
                keyboard()
                touchpad()
            } else {
                touchpad()
                keyboard()
            }
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
                ControlMode.KEYBOARD -> KeyboardPane(
                    vm, connected, compact = imeVisible, inputMode, onOpenVirtualKeyboard, Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CustomControls(
    vm: MainViewModel,
    settings: AppSettings,
    connected: Boolean,
    wide: Boolean,
    onOpenVirtualKeyboard: () -> Unit,
    onOpenAirMouse: () -> Unit,
    modifier: Modifier,
) {
    val mirrored by vm.mirrored.collectAsStateWithLifecycle()
    val modifiers by vm.modifiers.collectAsStateWithLifecycle()
    val language by vm.hostLanguage.collectAsStateWithLifecycle()
    val caps by vm.capsLock.collectAsStateWithLifecycle()
    val unsupported by vm.unsupportedCount.collectAsStateWithLifecycle()
    val state = GridWidgetState(
        controller = vm.input,
        enabled = connected,
        mirrored = mirrored,
        modifiers = modifiers,
        hostLanguage = language,
        capsLock = caps,
        unsupportedCount = unsupported,
        touchpadConfig = rememberTouchpadConfig(settings),
        haptics = settings.haptics,
        inputMode = settings.keyboardInputMode,
        onOpenVirtualKeyboard = onOpenVirtualKeyboard,
        onOpenAirMouse = onOpenAirMouse,
    )
    ControlGrid(modifier) { grid ->
        val layout = settings.customLayouts[CustomLayout.gridKey(grid.cols, grid.rows)]
            ?: CustomLayout.default(grid.cols, grid.rows, wide, settings.swapPanes)
        layout.items.forEachIndexed { index, item ->
            key(index, item) { GridWidget(item, state, Modifier.gridItem(grid, item)) }
        }
    }
}

@Composable
private fun rememberTouchpadConfig(settings: AppSettings): TouchpadConfig = remember(settings) {
    TouchpadConfig(
        pointerSpeed = settings.pointerSpeed,
        acceleration = settings.acceleration,
        scrollSpeed = settings.scrollSpeed,
        naturalScroll = settings.naturalScroll,
        tapToClick = settings.tapToClick,
    )
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
    val config = rememberTouchpadConfig(settings)
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
private fun KeyboardPane(
    vm: MainViewModel,
    connected: Boolean,
    compact: Boolean,
    inputMode: KeyboardInputMode,
    onOpenVirtualKeyboard: () -> Unit,
    modifier: Modifier,
) {
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
        inputMode = inputMode,
        onOpenVirtualKeyboard = onOpenVirtualKeyboard,
        modifier = modifier.verticalScroll(rememberScrollState()),
    )
}

@Composable
private fun ConnectionChip(hid: HidState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val discoverable = rememberDiscoverableRemaining(hid.discoverableUntil)
    val (icon: ImageVector, label: String) = when {
        hid.status != HidStatus.CONNECTED && discoverable != null ->
            Icons.Outlined.Visibility to stringResource(R.string.discoverable_short, formatRemaining(discoverable))
        hid.status == HidStatus.CONNECTED -> Icons.Outlined.BluetoothConnected to (hid.host?.name ?: stringResource(R.string.status_connected))
        hid.status == HidStatus.CONNECTING -> Icons.Outlined.Bluetooth to stringResource(R.string.status_connecting)
        hid.status == HidStatus.READY -> Icons.Outlined.Bluetooth to
            if (hid.reconnectAttempt > 0) stringResource(R.string.status_reconnecting, hid.reconnectAttempt)
            else stringResource(R.string.status_ready_short)
        else -> Icons.Outlined.BluetoothDisabled to statusLabel(hid.status)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp)) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        colors = if (hid.status == HidStatus.CONNECTED || discoverable != null) {
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
