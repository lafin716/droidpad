package com.droidpad.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** 200 Hz: the fastest rate available without the HIGH_SAMPLING_RATE_SENSORS permission. */
private const val SAMPLING_PERIOD_US = 5_000

/** Longest gap trusted between two samples; larger gaps (pauses, first sample) are dropped. */
private const val MAX_DT_SEC = 0.05f

/**
 * Shared thread for latency-sensitive sensors, so samples are not queued behind UI work
 * (layout, drawing, recomposition) on the main thread.
 */
private val sensorHandler: Handler by lazy {
    val thread = HandlerThread("droidpad-sensors", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    Handler(thread.looper)
}

@Composable
fun rememberSensorManager(): SensorManager {
    val context = LocalContext.current
    return remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
}

/**
 * Listens to [sensor] while in composition (and [enabled]). [onSample] runs on the main thread,
 * or on a dedicated sensor thread when [offMainThread] is set, with the raw values and the seconds elapsed since the previous sample, taken from event
 * timestamps so dropped or late frames do not distort integration.
 */
@Composable
fun SensorEffect(
    sensorManager: SensorManager,
    sensor: Sensor?,
    enabled: Boolean = true,
    offMainThread: Boolean = false,
    onSample: (values: FloatArray, dtSec: Float) -> Unit,
) {
    val current by rememberUpdatedState(onSample)
    DisposableEffect(sensorManager, sensor, enabled, offMainThread) {
        if (sensor == null || !enabled) return@DisposableEffect onDispose { }
        var lastNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val dt = if (lastNs == 0L) 0f else (event.timestamp - lastNs) / 1e9f
                lastNs = event.timestamp
                if (dt > 0f && dt <= MAX_DT_SEC) current(event.values, dt)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SAMPLING_PERIOD_US, if (offMainThread) sensorHandler else null)
        onDispose { sensorManager.unregisterListener(listener) }
    }
}

/** Keeps the current screen orientation while in composition, so waving the phone does not rotate the UI. */
@Composable
fun LockScreenOrientation() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose { activity.requestedOrientation = previous }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
