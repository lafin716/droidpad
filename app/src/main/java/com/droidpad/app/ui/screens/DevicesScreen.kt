package com.droidpad.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tablet
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidpad.app.MainViewModel
import com.droidpad.app.R
import com.droidpad.app.hid.HidState
import com.droidpad.app.hid.HidStatus
import com.droidpad.app.hid.HostDevice
import com.droidpad.app.ui.components.DiscoverableIndicator
import com.droidpad.app.ui.components.rememberDiscoverableRemaining

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
) {
    val hid by vm.hid.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                StatusCard(
                    hid = hid,
                    adapterName = vm.connection.adapterName,
                    onRequestPermissions = onRequestPermissions,
                    onEnableBluetooth = onEnableBluetooth,
                    onRetry = vm::refresh,
                    onDisconnect = vm::disconnect,
                    onCancelReconnect = vm::cancelReconnect,
                )
            }
            val usable = hid.status in setOf(HidStatus.READY, HidStatus.CONNECTING, HidStatus.CONNECTED, HidStatus.DISCONNECTING)
            if (usable) {
                item { PairingGuide(hid.discoverableUntil, onMakeDiscoverable) }
                item { SectionTitle(stringResource(R.string.devices_bonded)) }
                if (hid.bondedDevices.isEmpty()) {
                    item { EmptyText(stringResource(R.string.devices_bonded_empty)) }
                }
                items(hid.bondedDevices, key = { "b" + it.address }) { device ->
                    val isHost = hid.host?.address == device.address
                    DeviceRow(
                        device = device,
                        subtitle = when {
                            isHost && hid.status == HidStatus.CONNECTED -> stringResource(R.string.status_connected)
                            isHost && hid.status == HidStatus.CONNECTING -> stringResource(R.string.status_connecting)
                            device.address == settings.lastHostAddress -> stringResource(R.string.devices_last_host)
                            else -> device.address
                        },
                        highlighted = isHost && hid.status == HidStatus.CONNECTED,
                    ) {
                        if (isHost && hid.status == HidStatus.CONNECTED) {
                            OutlinedButton(onClick = vm::disconnect) { Text(stringResource(R.string.action_disconnect)) }
                        } else {
                            FilledTonalButton(
                                onClick = { vm.connect(device.address) },
                                enabled = hid.status == HidStatus.READY,
                            ) { Text(stringResource(R.string.action_connect)) }
                        }
                    }
                }
                item {
                    Row(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.devices_nearby), Modifier.weight(1f))
                        if (hid.discovering) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            TextButton(onClick = vm::stopDiscovery) { Text(stringResource(R.string.action_stop)) }
                        } else {
                            TextButton(onClick = vm::startDiscovery) {
                                Icon(Icons.Outlined.BluetoothSearching, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_scan))
                            }
                        }
                    }
                }
                if (hid.discoveredDevices.isEmpty()) {
                    item {
                        EmptyText(
                            stringResource(if (hid.discovering) R.string.devices_scanning else R.string.devices_nearby_empty),
                        )
                    }
                }
                items(hid.discoveredDevices, key = { "d" + it.address }) { device ->
                    DeviceRow(device, device.address, highlighted = false) {
                        FilledTonalButton(onClick = { vm.pair(device.address) }) { Text(stringResource(R.string.action_pair)) }
                    }
                }
                if (settings.lastHostAddress != null) {
                    item {
                        TextButton(onClick = vm::forgetHost) {
                            Text(stringResource(R.string.devices_forget, settings.lastHostName ?: settings.lastHostAddress!!))
                        }
                    }
                }
            }
            item { Diagnostics(hid) }
        }
    }
}

@Composable
private fun StatusCard(
    hid: HidState,
    adapterName: String?,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val problem = hid.status in setOf(HidStatus.NO_BLUETOOTH, HidStatus.UNSUPPORTED, HidStatus.ERROR)
    Card(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                problem -> colors.errorContainer
                hid.status == HidStatus.CONNECTED -> colors.primaryContainer
                else -> colors.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        problem -> Icons.Outlined.ErrorOutline
                        hid.status == HidStatus.CONNECTED -> Icons.Outlined.BluetoothConnected
                        else -> Icons.Outlined.Info
                    },
                    null,
                )
                Spacer(Modifier.width(12.dp))
                Text(statusLabel(hid.status), style = MaterialTheme.typography.titleMedium)
            }
            val detail = when (hid.status) {
                HidStatus.CONNECTED -> stringResource(R.string.status_connected_to, hid.host?.name ?: "")
                HidStatus.READY ->
                    if (hid.reconnectAttempt > 0) stringResource(R.string.status_reconnecting, hid.reconnectAttempt)
                    else stringResource(R.string.status_ready_detail, adapterName ?: "DroidPad")
                HidStatus.PERMISSION_REQUIRED -> stringResource(R.string.status_permission_detail)
                HidStatus.BLUETOOTH_OFF -> stringResource(R.string.status_bt_off_detail)
                HidStatus.UNSUPPORTED -> stringResource(R.string.status_unsupported_detail)
                HidStatus.NO_BLUETOOTH -> stringResource(R.string.status_no_bluetooth_detail)
                HidStatus.ERROR -> hid.error ?: stringResource(R.string.status_error)
                else -> null
            }
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (hid.error != null && hid.status != HidStatus.ERROR) {
                Text(hid.error, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (hid.status) {
                    HidStatus.PERMISSION_REQUIRED -> Button(onClick = onRequestPermissions) { Text(stringResource(R.string.action_grant)) }
                    HidStatus.BLUETOOTH_OFF -> Button(onClick = onEnableBluetooth) { Text(stringResource(R.string.action_enable_bt)) }
                    HidStatus.ERROR, HidStatus.UNSUPPORTED -> OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    HidStatus.CONNECTED -> OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.action_disconnect)) }
                    HidStatus.READY -> if (hid.reconnectAttempt > 0) {
                        OutlinedButton(onClick = onCancelReconnect) { Text(stringResource(R.string.action_cancel_reconnect)) }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun PairingGuide(discoverableUntil: Long?, onMakeDiscoverable: () -> Unit) {
    val remaining = rememberDiscoverableRemaining(discoverableUntil)
    Card(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.guide_steps), style = MaterialTheme.typography.bodyMedium)
            if (remaining != null) DiscoverableIndicator(remaining, Modifier.padding(vertical = 4.dp))
            FilledTonalButton(onClick = onMakeDiscoverable) {
                Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (remaining != null) R.string.action_discoverable_extend else R.string.action_discoverable))
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: HostDevice,
    subtitle: String,
    highlighted: Boolean,
    trailing: @Composable () -> Unit,
) {
    Card(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        ListItem(
            headlineContent = { Text(device.name) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(Icons.Outlined.Tablet, null) },
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
    )
}

@Composable
private fun Diagnostics(hid: HidState) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        val support = when (hid.status) {
            HidStatus.UNSUPPORTED -> stringResource(R.string.diag_hid_no)
            HidStatus.READY, HidStatus.CONNECTING, HidStatus.CONNECTED, HidStatus.DISCONNECTING -> stringResource(R.string.diag_hid_yes)
            else -> stringResource(R.string.diag_hid_unknown)
        }
        Text(support, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.diag_leds, hid.leds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
