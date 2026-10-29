package com.droidpad.app.ui.screens

import android.hardware.Sensor
import android.os.SystemClock
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
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidpad.app.MainViewModel
import com.droidpad.app.R
import com.droidpad.app.hid.HidStatus
import com.droidpad.app.input.motion.DeskMouseConfig
import com.droidpad.app.input.motion.DeskMouseDebug
import com.droidpad.app.input.motion.DeskMouseEngine
import com.droidpad.app.input.motion.DeskPhase
import com.droidpad.app.input.touchpad.TouchpadAction
import com.droidpad.app.ui.components.LockScreenOrientation
import com.droidpad.app.ui.components.MouseButtons
import com.droidpad.app.ui.components.SensorEffect
import com.droidpad.app.ui.components.rememberSensorManager
import java.util.Locale

private const val READOUT_INTERVAL_MS = 100L

/**
 * Labs: prototype of a mouse that is used by sliding the phone on the desk. Acceleration is
 * double-integrated by [DeskMouseEngine]; all tuning here is per-session and not persisted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeskMouseLabScreen(vm: MainViewModel, onBack: () -> Unit) {
    LockScreenOrientation()
    val hid by vm.hid.collectAsStateWithLifecycle()
    val connected = hid.status == HidStatus.CONNECTED
    val currentConnected by rememberUpdatedState(connected)

    val sensors = rememberSensorManager()
    val linear = remember(sensors) { sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) }
    val raw = remember(sensors) { sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    var useLinear by remember { mutableStateOf(linear != null) }
    var running by remember { mutableStateOf(false) }

    var speed by remember { mutableFloatStateOf(DeskMouseConfig().speed) }
    var noiseFloor by remember { mutableFloatStateOf(DeskMouseConfig().noiseFloor) }
    var stillSeconds by remember { mutableFloatStateOf(DeskMouseConfig().stillSeconds) }
    var friction by remember { mutableFloatStateOf(DeskMouseConfig().friction) }

    val engine = remember {
        DeskMouseEngine { dx, dy -> if (currentConnected) vm.input.onTouchpad(TouchpadAction.Move(dx, dy)) }
    }
    // Build the config in this scope so slider changes (read only inside the cards) re-run the effect.
    val config = DeskMouseConfig(speed = speed, noiseFloor = noiseFloor, stillSeconds = stillSeconds, friction = friction)
    SideEffect { engine.config = config }

    var debug by remember { mutableStateOf(DeskMouseDebug()) }
    var sampleHz by remember { mutableIntStateOf(0) }
    val counter = remember { LongArray(2) } // [samples since last readout, last readout time]
    val sensor = if (useLinear) linear else raw
    SensorEffect(sensors, sensor, enabled = running) { v, dt ->
        engine.onAcceleration(v[0], v[1], dt)
        counter[0]++
        val now = SystemClock.uptimeMillis()
        if (now - counter[1] >= READOUT_INTERVAL_MS) {
            sampleHz = if (counter[1] == 0L) 0 else (counter[0] * 1000 / (now - counter[1])).toInt()
            counter[0] = 0; counter[1] = now
            debug = engine.debug
        }
    }
    val start = {
        engine.calibrate()
        debug = engine.debug
        running = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lab_desk_title)) },
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
            LabCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Science, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.lab_experimental),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(stringResource(R.string.lab_desk_instructions), style = MaterialTheme.typography.bodyMedium)
                if (!connected) {
                    Text(stringResource(R.string.not_connected_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (raw == null) {
                    Text(stringResource(R.string.lab_desk_no_sensor), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            LabCard {
                Text(
                    stringResource(
                        when {
                            !running -> R.string.lab_phase_paused
                            debug.phase == DeskPhase.CALIBRATING -> R.string.lab_phase_calibrating
                            debug.phase == DeskPhase.STILL -> R.string.lab_phase_still
                            else -> R.string.lab_phase_moving
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (running && debug.phase == DeskPhase.MOVING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    String.format(
                        Locale.US, "a  %+6.2f  %+6.2f m/s²\nv  %+6.3f  %+6.3f m/s\n%d Hz",
                        debug.ax, debug.ay, debug.vx, debug.vy, if (running) sampleHz else 0,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(onClick = { running = false }) {
                            Icon(Icons.Outlined.Pause, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.lab_pause), Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Button(onClick = start, enabled = sensor != null) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.lab_start), Modifier.padding(start = 8.dp))
                        }
                    }
                    FilledTonalButton(onClick = start, enabled = sensor != null) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.lab_calibrate), Modifier.padding(start = 8.dp))
                    }
                }
                MouseButtons(
                    enabled = connected,
                    onPress = { vm.input.onTouchpad(TouchpadAction.Press(it)) },
                    onRelease = { vm.input.onTouchpad(TouchpadAction.Release(it)) },
                )
            }
            LabCard {
                Text(stringResource(R.string.lab_sensor_source), style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true, false).forEachIndexed { i, lin ->
                        SegmentedButton(
                            selected = useLinear == lin,
                            onClick = {
                                useLinear = lin
                                if (running) start()
                            },
                            enabled = (if (lin) linear else raw) != null,
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            label = { Text(stringResource(if (lin) R.string.lab_sensor_linear else R.string.lab_sensor_raw)) },
                        )
                    }
                }
                TuningSlider(stringResource(R.string.lab_speed), speed, 0.2f..5f, "%.1f×") { speed = it }
                TuningSlider(stringResource(R.string.lab_noise_floor), noiseFloor, 0.02f..0.6f, "%.2f m/s²") { noiseFloor = it }
                TuningSlider(stringResource(R.string.lab_still_time), stillSeconds * 1000, 30f..300f, "%.0f ms") { stillSeconds = it / 1000 }
                TuningSlider(stringResource(R.string.lab_friction), friction, 0f..8f, "%.1f") { friction = it }
            }
        }
    }
}

@Composable
private fun LabCard(content: @Composable () -> Unit) {
    Card(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun TuningSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, format, value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
