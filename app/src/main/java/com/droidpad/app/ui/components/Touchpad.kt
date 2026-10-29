package com.droidpad.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import android.view.InputDevice
import android.view.View
import java.util.WeakHashMap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidpad.app.R
import com.droidpad.app.hid.MouseButton
import com.droidpad.app.input.touchpad.TouchpadAction
import com.droidpad.app.input.touchpad.TouchpadConfig
import com.droidpad.app.input.touchpad.TouchpadGestureEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Multi-touch surface that feeds [TouchpadGestureEngine] and forwards its actions. */
@Composable
fun TouchpadSurface(
    config: TouchpadConfig,
    haptics: Boolean,
    enabled: Boolean,
    onAction: (TouchpadAction) -> Unit,
    modifier: Modifier = Modifier,
    showHint: Boolean = true,
) {
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val currentOnAction by rememberUpdatedState(onAction)
    val currentHaptics by rememberUpdatedState(haptics)
    var dragging by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }

    val engine = remember {
        TouchpadGestureEngine(config.copy(density = density)) { action ->
            if (action is TouchpadAction.Click && currentHaptics) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            currentOnAction(action)
        }
    }
    LaunchedEffect(config, density) { engine.config = config.copy(density = density) }
    UnbufferedPointerDispatch()

    val shape = RoundedCornerShape(28.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(shape)
            .background(if (dragging) colors.primaryContainer else colors.surfaceContainerHigh)
            .border(1.dp, if (touched) colors.primary.copy(alpha = 0.5f) else colors.outlineVariant, shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    var longPress: Job? = null
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (c in event.changes) {
                                val id = c.id.value
                                when {
                                    c.pressed && !c.previousPressed -> {
                                        val wasIdle = !touched
                                        engine.onDown(id, c.position.x, c.position.y, c.uptimeMillis)
                                        touched = true
                                        if (wasIdle) {
                                            longPress?.cancel()
                                            longPress = launch {
                                                delay(engine.config.longPressMs)
                                                if (engine.onLongPressTimeout(SystemClock.uptimeMillis())) {
                                                    dragging = true
                                                    if (currentHaptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                            }
                                        } else {
                                            longPress?.cancel()
                                        }
                                    }
                                    !c.pressed && c.previousPressed -> {
                                        engine.onUp(id, c.uptimeMillis)
                                    }
                                    c.pressed -> engine.onMove(id, c.position.x, c.position.y, c.uptimeMillis)
                                }
                                c.consume()
                            }
                            if (event.changes.none { it.pressed }) {
                                longPress?.cancel()
                                touched = false
                                dragging = false
                            }
                            if (event.type == PointerEventType.Exit && event.changes.none { it.pressed }) {
                                engine.onCancel()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showHint && !touched) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.TouchApp,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    stringResource(if (enabled) R.string.touchpad_hint else R.string.touchpad_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
        if (dragging) {
            Text(
                stringResource(R.string.touchpad_dragging),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            )
        }
    }
}

private val unbufferedRequests = WeakHashMap<View, Int>()

/**
 * While in composition, asks the window to deliver touch events as soon as they arrive instead
 * of batching them once per display frame. That removes up to a frame of latency and hands the
 * pointer engines every hardware sample. Reference-counted per view; main thread only.
 */
@Composable
fun UnbufferedPointerDispatch() {
    val view = LocalView.current
    DisposableEffect(view) {
        val count = unbufferedRequests[view] ?: 0
        unbufferedRequests[view] = count + 1
        if (count == 0) view.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
        onDispose {
            val left = (unbufferedRequests[view] ?: 1) - 1
            if (left <= 0) {
                unbufferedRequests.remove(view)
                view.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_NONE)
            } else {
                unbufferedRequests[view] = left
            }
        }
    }
}

/** Physical-style left / right buttons under the touchpad (press-and-hold supported). */
@Composable
fun MouseButtons(
    enabled: Boolean,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HoldButton(stringResource(R.string.mouse_left), enabled, { onPress(MouseButton.LEFT) }, { onRelease(MouseButton.LEFT) }, Modifier.weight(1f).fillMaxHeight())
        HoldButton(stringResource(R.string.mouse_right), enabled, { onPress(MouseButton.RIGHT) }, { onRelease(MouseButton.RIGHT) }, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
fun HoldButton(
    label: String,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
    monospace: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentDown by rememberUpdatedState(onDown)
    val currentUp by rememberUpdatedState(onUp)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (!pressed && event.changes.any { it.pressed && !it.previousPressed }) {
                        pressed = true
                        currentDown()
                    }
                    event.changes.forEach { it.consume() }
                    if (pressed && event.changes.none { it.pressed }) {
                        pressed = false
                        currentUp()
                    }
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        color = when {
            !enabled -> colors.surfaceContainerHighest.copy(alpha = 0.5f)
            pressed -> colors.primary
            else -> colors.secondaryContainer
        },
        contentColor = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            pressed -> colors.onPrimary
            else -> colors.onSecondaryContainer
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (content != null) {
                content()
            } else {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                    maxLines = 1,
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (pressed) currentUp() }
    }
}
