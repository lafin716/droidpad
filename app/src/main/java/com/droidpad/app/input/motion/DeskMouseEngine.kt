package com.droidpad.app.input.motion

import kotlin.math.abs
import kotlin.math.truncate

data class DeskMouseConfig(
    val speed: Float = 1.0f,
    /** Pointer counts per metre of travel at speed 1. */
    val countsPerMeter: Float = 20000f,
    /** Per-axis acceleration below this (m/s²) is treated as sensor noise. */
    val noiseFloor: Float = 0.12f,
    /** The phone counts as stopped after this long without acceleration; velocity is zeroed (ZUPT). */
    val stillSeconds: Float = 0.10f,
    /** Velocity decay per second while moving, to limit drift between stops. */
    val friction: Float = 2.0f,
    val calibrationSeconds: Float = 1.0f,
)

enum class DeskPhase { CALIBRATING, STILL, MOVING }

data class DeskMouseDebug(
    val phase: DeskPhase = DeskPhase.CALIBRATING,
    val ax: Float = 0f,
    val ay: Float = 0f,
    val vx: Float = 0f,
    val vy: Float = 0f,
)

/**
 * Experimental "slide the phone on the desk" mouse. The phone lies screen-up with its top pointing
 * away from the user. Acceleration is integrated twice: bias is removed by a calibration window,
 * small readings are clamped to zero, and velocity is reset whenever the phone is still (zero
 * velocity update) so drift cannot build up between strokes. Inherently imprecise.
 */
class DeskMouseEngine(
    var config: DeskMouseConfig = DeskMouseConfig(),
    private val emit: (dx: Int, dy: Int) -> Unit,
) {
    private var biasX = 0f
    private var biasY = 0f
    private var sumX = 0.0
    private var sumY = 0.0
    private var calTime = 0f
    private var calSamples = 0
    private var stillTime = 0f
    private var vx = 0f
    private var vy = 0f
    private var remX = 0f
    private var remY = 0f

    var debug = DeskMouseDebug()
        private set

    /** Starts a new bias calibration; the phone must be at rest for [DeskMouseConfig.calibrationSeconds]. */
    fun calibrate() {
        sumX = 0.0; sumY = 0.0
        calTime = 0f; calSamples = 0
        stillTime = 0f
        stop()
        debug = DeskMouseDebug(DeskPhase.CALIBRATING)
    }

    /** Device-frame acceleration (m/s², gravity removed or constant) over [dtSec] seconds. */
    fun onAcceleration(ax: Float, ay: Float, dtSec: Float) {
        if (dtSec <= 0f) return
        if (debug.phase == DeskPhase.CALIBRATING) {
            sumX += ax; sumY += ay
            calSamples++
            calTime += dtSec
            if (calTime >= config.calibrationSeconds) {
                biasX = (sumX / calSamples).toFloat()
                biasY = (sumY / calSamples).toFloat()
                stillTime = config.stillSeconds
                debug = DeskMouseDebug(DeskPhase.STILL)
            }
            return
        }
        val x = floored(ax - biasX)
        val y = floored(ay - biasY)
        if (x == 0f && y == 0f) stillTime += dtSec else stillTime = 0f
        val phase = if (stillTime >= config.stillSeconds) {
            stop()
            DeskPhase.STILL
        } else {
            val keep = (1f - config.friction * dtSec).coerceIn(0f, 1f)
            vx = (vx + x * dtSec) * keep
            vy = (vy + y * dtSec) * keep
            val gain = config.countsPerMeter * config.speed
            // Device +Y points away from the user, which is "up" on the host screen.
            remX += vx * dtSec * gain
            remY -= vy * dtSec * gain
            val dx = truncate(remX).toInt()
            val dy = truncate(remY).toInt()
            remX -= dx; remY -= dy
            if (dx != 0 || dy != 0) emit(dx, dy)
            DeskPhase.MOVING
        }
        debug = DeskMouseDebug(phase, x, y, vx, vy)
    }

    private fun stop() {
        vx = 0f; vy = 0f
        remX = 0f; remY = 0f
    }

    private fun floored(a: Float): Float = if (abs(a) < config.noiseFloor) 0f else a
}
