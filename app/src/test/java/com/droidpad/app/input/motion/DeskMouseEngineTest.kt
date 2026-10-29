package com.droidpad.app.input.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskMouseEngineTest {
    private var dx = 0
    private var dy = 0
    private val engine = DeskMouseEngine(DeskMouseConfig(friction = 0f, countsPerMeter = 10000f)) { x, y ->
        dx += x; dy += y
    }
    private val dt = 0.01f

    private fun feed(ax: Float, ay: Float, seconds: Float) =
        repeat(Math.round(seconds / dt)) { engine.onAcceleration(ax, ay, dt) }

    private fun calibrated(biasX: Float = 0f, biasY: Float = 0f) {
        engine.calibrate()
        feed(biasX, biasY, 1.05f)
    }

    @Test
    fun calibrationLearnsBias() {
        calibrated(biasX = 0.3f, biasY = -0.2f)
        assertEquals(DeskPhase.STILL, engine.debug.phase)
        feed(0.3f, -0.2f, 2f)
        assertEquals(0, dx); assertEquals(0, dy)
        assertEquals(DeskPhase.STILL, engine.debug.phase)
    }

    @Test
    fun noMotionDuringCalibration() {
        engine.calibrate()
        feed(5f, 5f, 0.5f)
        assertEquals(0, dx); assertEquals(0, dy)
        assertEquals(DeskPhase.CALIBRATING, engine.debug.phase)
    }

    @Test
    fun accelerateThenBrakeMovesRight() {
        calibrated()
        feed(2f, 0f, 0.2f) // v = 0.4 m/s
        feed(-2f, 0f, 0.2f) // back to 0; ~8 cm travelled
        feed(0f, 0f, 0.2f)
        assertTrue("dx=$dx", dx in 700..900)
        assertEquals(0, dy)
        assertEquals(DeskPhase.STILL, engine.debug.phase)
    }

    @Test
    fun pushingAwayMovesUp() {
        calibrated()
        feed(0f, 2f, 0.2f)
        feed(0f, -2f, 0.2f)
        assertTrue("dy=$dy", dy < -700)
    }

    @Test
    fun zeroVelocityUpdateStopsLeftoverDrift() {
        calibrated()
        feed(2f, 0f, 0.2f) // never braked: residual velocity
        feed(0f, 0f, 0.2f) // coasts only until the still window elapses
        val afterStop = dx
        feed(0f, 0f, 2f)
        assertEquals(afterStop, dx)
        assertEquals(0f, engine.debug.vx)
        assertEquals(DeskPhase.STILL, engine.debug.phase)
    }

    @Test
    fun noiseBelowFloorIsIgnored() {
        calibrated()
        feed(0.1f, -0.1f, 2f)
        assertEquals(0, dx); assertEquals(0, dy)
    }
}
