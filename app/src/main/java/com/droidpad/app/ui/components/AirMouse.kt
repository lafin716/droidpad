package com.droidpad.app.ui.components

import android.hardware.Sensor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ControlCamera
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidpad.app.R
import com.droidpad.app.hid.MouseButton
import com.droidpad.app.input.motion.AirMouseConfig
import com.droidpad.app.input.motion.AirMouseEngine
import com.droidpad.app.input.touchpad.TouchpadAction
import kotlin.math.truncate

/** A press on the move pad shorter than this is a left click. */
private const val TAP_MILLIS = 200L
private const val SCROLL_DP_PER_TICK = 18f

/**
 * Full-screen gyro "air mouse". Point the phone like a TV remote: turning it moves the pointer.
 * With [holdToMove] the pointer only moves while the pad is held; otherwise it always moves and
 * holding the pad pauses it (to re-centre the hand). A quick tap on the pad is a left click.
 */
@Composable
fun AirMouse(
    enabled: Boolean,
    speed: Float,
    holdToMove: Boolean,
    scrollSpeed: Float,
    naturalScroll: Boolean,
    onAction: (TouchpadAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    LockScreenOrientation()
    val currentAction by rememberUpdatedState(onAction)
    val currentEnabled by rememberUpdatedState(enabled)
    val engine = remember {
        AirMouseEngine { dx, dy -> if (currentEnabled) currentAction(TouchpadAction.Move(dx, dy)) }
    }
    var padPressed by remember { mutableStateOf(false) }

    val sensors = rememberSensorManager()
    val gyro = remember(sensors) { sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    // Read padPressed here, not only inside the Surface content below: the content lambda is its
    // own recompose scope, so a press would otherwise never re-run this SideEffect.
    val active = enabled && gyro != null && padPressed == holdToMove
    SideEffect {
        engine.config = AirMouseConfig(speed = speed)
        engine.active = active
    }
    val gravity = remember(sensors) {
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    // Off the main thread: the engine and the HID sender are thread-safe, and UI work there
    // would otherwise delay and bunch up the 200 Hz gyro samples.
    SensorEffect(sensors, gravity, offMainThread = true) { v, _ -> engine.onGravity(v[0], v[1], v[2]) }
    SensorEffect(sensors, gyro, offMainThread = true) { v, dt -> engine.onGyro(v[0], v[1], v[2], dt) }
    UnbufferedPointerDispatch()

    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = colors.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.air_mouse_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
            }
            val hint = when {
                gyro == null -> stringResource(R.string.air_mouse_no_gyro)
                !enabled -> stringResource(R.string.not_connected_hint)
                holdToMove -> stringResource(R.string.air_mouse_hint_hold)
                else -> stringResource(R.string.air_mouse_hint_always)
            }
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MovePad(
                    enabled = enabled && gyro != null,
                    pressed = padPressed,
                    holdToMove = holdToMove,
                    onPressedChange = { padPressed = it },
                    onTap = {
                        currentAction(TouchpadAction.Click(MouseButton.LEFT))
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                ScrollStrip(enabled, scrollSpeed, naturalScroll, onAction, Modifier.width(72.dp).fillMaxHeight())
            }
            MouseButtons(
                enabled = enabled,
                onPress = { onAction(TouchpadAction.Press(it)) },
                onRelease = { onAction(TouchpadAction.Release(it)) },
            )
        }
    }
}

@Composable
private fun MovePad(
    enabled: Boolean,
    pressed: Boolean,
    holdToMove: Boolean,
    onPressedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val currentPressed by rememberUpdatedState(onPressedChange)
    val currentTap by rememberUpdatedState(onTap)
    val colors = MaterialTheme.colorScheme
    val moving = enabled && pressed == holdToMove
    Surface(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                currentPressed(true)
                var up = down
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    up = change
                    if (!change.pressed) break
                }
                currentPressed(false)
                if (up.uptimeMillis - down.uptimeMillis < TAP_MILLIS) currentTap()
            }
        },
        shape = RoundedCornerShape(24.dp),
        color = when {
            !enabled -> colors.surfaceContainerHighest.copy(alpha = 0.5f)
            moving -> colors.primaryContainer
            else -> colors.surfaceContainerHigh
        },
        contentColor = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            moving -> colors.onPrimaryContainer
            else -> colors.onSurfaceVariant
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.ControlCamera, null, Modifier.size(56.dp))
            Text(
                stringResource(if (holdToMove) R.string.air_mouse_pad_hold else R.string.air_mouse_pad_pause),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.air_mouse_pad_tap),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Drag up/down to scroll, following the touchpad's scroll speed and direction settings. */
@Composable
private fun ScrollStrip(
    enabled: Boolean,
    scrollSpeed: Float,
    naturalScroll: Boolean,
    onAction: (TouchpadAction) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current.density
    val step = SCROLL_DP_PER_TICK * density / scrollSpeed.coerceAtLeast(0.1f)
    val sign = if (naturalScroll) 1f else -1f
    val currentAction by rememberUpdatedState(onAction)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.pointerInput(enabled, step, sign) {
            if (!enabled) return@pointerInput
            var rem = 0f
            detectVerticalDragGestures(onDragStart = { rem = 0f }) { change, dy ->
                change.consume()
                rem += sign * dy / step
                val ticks = truncate(rem).toInt()
                rem -= ticks
                if (ticks != 0) currentAction(TouchpadAction.Scroll(ticks, 0))
            }
        },
        shape = RoundedCornerShape(24.dp),
        color = if (enabled) colors.secondaryContainer else colors.surfaceContainerHighest.copy(alpha = 0.5f),
        contentColor = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = 0.38f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.UnfoldMore, null)
                Text(stringResource(R.string.air_mouse_scroll), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
