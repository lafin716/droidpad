package com.droidpad.app

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.droidpad.app.hid.HidConnectionManager
import com.droidpad.app.hid.HidStatus
import com.droidpad.app.ui.screens.ControlScreen
import com.droidpad.app.ui.screens.DevicesScreen
import com.droidpad.app.ui.screens.LayoutEditorScreen
import com.droidpad.app.ui.screens.SettingsScreen
import com.droidpad.app.ui.theme.DroidPadTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as DroidPadApp).container }

    private val vm: MainViewModel by viewModels {
        viewModelFactory {
            initializer { MainViewModel(container.settings, container.connection, container.input) }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }

    // The result code is the granted duration in seconds, or RESULT_CANCELED (0) when declined.
    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            container.connection.onDiscoverable(result.resultCode)
            vm.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val hid by vm.hid.collectAsStateWithLifecycle()
            LaunchedEffect(settings.keepScreenOn, hid.status) {
                if (settings.keepScreenOn && hid.status == HidStatus.CONNECTED) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            DroidPadTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DroidPadRoot(
                        vm = vm,
                        onRequestPermissions = ::requestPermissions,
                        onEnableBluetooth = ::requestEnableBluetooth,
                        onMakeDiscoverable = ::requestDiscoverable,
                    )
                }
            }
        }
        if (!container.connection.hasPermissions()) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        container.connection.start()
    }

    override fun onDestroy() {
        // Keep the HID registration alive across rotations / fold changes; drop it when the user leaves.
        if (isFinishing) container.connection.shutdown()
        super.onDestroy()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(HidConnectionManager.REQUIRED_PERMISSIONS)
    }

    private fun requestEnableBluetooth() {
        runCatching { enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            .onFailure { vm.refresh() }
    }

    private fun requestDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, HidConnectionManager.DEFAULT_DISCOVERABLE_SECONDS)
        runCatching { discoverableLauncher.launch(intent) }
    }
}

private enum class Screen { CONTROL, DEVICES, SETTINGS, LAYOUT_EDITOR }

@Composable
private fun DroidPadRoot(
    vm: MainViewModel,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
) {
    var screen by rememberSaveable { mutableIntStateOf(Screen.CONTROL.ordinal) }
    val current = Screen.entries[screen]
    BackHandler(enabled = current != Screen.CONTROL) {
        screen = if (current == Screen.LAYOUT_EDITOR) Screen.SETTINGS.ordinal else Screen.CONTROL.ordinal
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        when (current) {
            Screen.CONTROL -> ControlScreen(
                vm = vm,
                wide = wide,
                onOpenDevices = { screen = Screen.DEVICES.ordinal },
                onOpenSettings = { screen = Screen.SETTINGS.ordinal },
            )
            Screen.DEVICES -> DevicesScreen(
                vm = vm,
                onBack = { screen = Screen.CONTROL.ordinal },
                onRequestPermissions = onRequestPermissions,
                onEnableBluetooth = onEnableBluetooth,
                onMakeDiscoverable = onMakeDiscoverable,
            )
            Screen.SETTINGS -> SettingsScreen(
                vm = vm,
                onBack = { screen = Screen.CONTROL.ordinal },
                onEditLayout = { screen = Screen.LAYOUT_EDITOR.ordinal },
            )
            Screen.LAYOUT_EDITOR -> LayoutEditorScreen(vm = vm, wide = wide, onBack = { screen = Screen.SETTINGS.ordinal })
        }
    }
}
