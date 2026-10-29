package com.droidpad.app.input.motion

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.truncate

data class AirMouseConfig(
    val speed: Float = 1.0f,
    /** Pointer counts per radian of rotation at speed 1. */
    val countsPerRadian: Float = 900f,
    /** Rotation rates below this (rad/s) are treated as hand tremor / gyro drift. */
    val deadzone: Float = 0.03f,
    /** Weight of the newest sample in the exponential low-pass filter (1 = no smoothing). */
    val smoothing: Float = 0.4f,
)

/**
 * Turns gyroscope rotation into relative pointer motion, like a TV remote / Wii pointer.
 *
 * Turning the phone left/right (rotation about the world's vertical axis, found with the gravity
 * vector) moves the pointer horizontally; tilting its top up/down (rotation about the device X
 * axis) moves it vertically. Using gravity makes this work whether the phone is held flat or
 * upright. Gyro rates are integrated once, so there is no position drift.
 *
 * Thread-safe: samples may arrive on a sensor thread while [config] / [active] change on the UI thread.
 */
class AirMouseEngine(
    @Volatile var config: AirMouseConfig = AirMouseConfig(),
    private val emit: (dx: Int, dy: Int) -> Unit,
) {
    private var gx = 0f
    private var gy = 0f
    private var gz = 1f
    private var rateX = 0f
    private var rateY = 0f
    private var remX = 0f
    private var remY = 0f

    /** Motion is only emitted while active (e.g. while the clutch is held). */
    @Volatile var active: Boolean = false
        @Synchronized set(value) {
            if (field != value) reset()
            field = value
        }

    /** Gravity (or raw accelerometer) reading in device coordinates, any unit. */
    @Synchronized
    fun onGravity(x: Float, y: Float, z: Float) {
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1e-3f) return
        gx = x / n; gy = y / n; gz = z / n
    }

    /** Angular velocity in rad/s (Android gyroscope axes) over [dtSec] seconds. */
    @Synchronized
    fun onGyro(wx: Float, wy: Float, wz: Float, dtSec: Float) {
        if (!active || dtSec <= 0f) return
        // Android rates are counter-clockwise positive: turning left / tilting up is positive.
        val yaw = wx * gx + wy * gy + wz * gz
        val a = config.smoothing.coerceIn(0.01f, 1f)
        rateX += a * (-yaw - rateX)
        rateY += a * (-wx - rateY)
        val gain = config.countsPerRadian * config.speed
        remX += deadzoned(rateX) * dtSec * gain
        remY += deadzoned(rateY) * dtSec * gain
        val dx = truncate(remX).toInt()
        val dy = truncate(remY).toInt()
        remX -= dx; remY -= dy
        if (dx != 0 || dy != 0) emit(dx, dy)
    }

    @Synchronized
    fun reset() {
        rateX = 0f; rateY = 0f
        remX = 0f; remY = 0f
    }

    private fun deadzoned(rate: Float): Float {
        val m = abs(rate) - config.deadzone
        return if (m <= 0f) 0f else m * sign(rate)
    }
}
