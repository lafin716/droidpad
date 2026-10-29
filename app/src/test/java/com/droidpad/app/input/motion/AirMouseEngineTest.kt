package com.droidpad.app.input.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirMouseEngineTest {
    private var dx = 0
    private var dy = 0
    private val engine = AirMouseEngine(AirMouseConfig(smoothing = 1f, deadzone = 0f, countsPerRadian = 1000f)) { x, y ->
        dx += x; dy += y
    }

    private fun spin(wx: Float, wy: Float, wz: Float, steps: Int = 100, dt: Float = 0.01f) =
        repeat(steps) { engine.onGyro(wx, wy, wz, dt) }

    @Test
    fun inactiveEmitsNothing() {
        spin(0f, 0f, 1f)
        assertEquals(0, dx); assertEquals(0, dy)
    }

    @Test
    fun turningLeftWhileFlatMovesLeft() {
        engine.active = true
        engine.onGravity(0f, 0f, 9.8f)
        spin(0f, 0f, 1f) // 1 rad/s CCW seen from above for 1 s
        assertEquals(-1000, dx)
        assertEquals(0, dy)
    }

    @Test
    fun turningLeftWhileUprightMovesLeft() {
        engine.active = true
        engine.onGravity(0f, 9.8f, 0f)
        spin(0f, 1f, 0f)
        assertEquals(-1000, dx)
    }

    @Test
    fun rotationAboutTheScreenNormalIsIgnoredWhenUpright() {
        engine.active = true
        engine.onGravity(0f, 9.8f, 0f)
        spin(0f, 0f, 1f)
        assertEquals(0, dx)
    }

    @Test
    fun tiltingUpMovesUp() {
        engine.active = true
        spin(0.5f, 0f, 0f)
        assertEquals(-500, dy)
    }

    @Test
    fun speedScalesMotion() {
        engine.config = engine.config.copy(speed = 2f)
        engine.active = true
        spin(0f, 0f, -0.5f)
        assertEquals(1000, dx)
    }

    @Test
    fun deadzoneSwallowsTremor() {
        engine.config = engine.config.copy(deadzone = 0.05f)
        engine.active = true
        spin(0.04f, 0f, -0.04f, steps = 500)
        assertEquals(0, dx); assertEquals(0, dy)
    }

    @Test
    fun smoothingRampsUp() {
        engine.config = engine.config.copy(smoothing = 0.2f)
        engine.active = true
        engine.onGyro(0f, 0f, -1f, 0.1f)
        assertTrue(dx in 1..99)
    }
}
