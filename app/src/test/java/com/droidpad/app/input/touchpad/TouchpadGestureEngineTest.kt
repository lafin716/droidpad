package com.droidpad.app.input.touchpad

import com.droidpad.app.hid.MouseButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadGestureEngineTest {
    private val actions = ArrayList<TouchpadAction>()
    private val engine = TouchpadGestureEngine(TouchpadConfig(acceleration = false)) { actions += it }

    @Test
    fun tapIsLeftClick() {
        engine.onDown(0, 10f, 10f, 0)
        engine.onUp(0, 100)
        assertEquals(listOf(TouchpadAction.Click(MouseButton.LEFT)), actions)
    }

    @Test
    fun twoTapsAreTwoClicks() {
        engine.onDown(0, 10f, 10f, 0); engine.onUp(0, 80)
        engine.onDown(0, 11f, 10f, 150); engine.onUp(0, 230)
        assertEquals(2, actions.count { it == TouchpadAction.Click(MouseButton.LEFT) })
    }

    @Test
    fun slowPressIsNotAClick() {
        engine.onDown(0, 10f, 10f, 0)
        engine.onUp(0, 400)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun tapToClickCanBeDisabled() {
        engine.config = engine.config.copy(tapToClick = false)
        engine.onDown(0, 10f, 10f, 0)
        engine.onUp(0, 50)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun twoFingerTapIsRightClick() {
        engine.onDown(0, 10f, 10f, 0)
        engine.onDown(1, 60f, 10f, 20)
        engine.onUp(1, 120)
        engine.onUp(0, 130)
        assertEquals(listOf(TouchpadAction.Click(MouseButton.RIGHT)), actions)
    }

    @Test
    fun threeFingerTapIsMiddleClick() {
        engine.onDown(0, 10f, 10f, 0)
        engine.onDown(1, 60f, 10f, 10)
        engine.onDown(2, 110f, 10f, 20)
        engine.onUp(0, 100); engine.onUp(1, 110); engine.onUp(2, 120)
        assertEquals(listOf(TouchpadAction.Click(MouseButton.MIDDLE)), actions)
    }

    @Test
    fun oneFingerMoveMovesPointerWithoutClicking() {
        engine.onDown(0, 0f, 0f, 0)
        engine.onMove(0, 20f, 0f, 16)
        engine.onMove(0, 40f, 10f, 32)
        engine.onUp(0, 60)
        val moves = actions.filterIsInstance<TouchpadAction.Move>()
        assertEquals(40, moves.sumOf { it.dx })
        assertEquals(10, moves.sumOf { it.dy })
        assertTrue(actions.none { it is TouchpadAction.Click })
    }

    @Test
    fun smallJitterBelowThresholdDoesNotMove() {
        engine.onDown(0, 0f, 0f, 0)
        engine.onMove(0, 1f, 1f, 16)
        engine.onUp(0, 60)
        assertEquals(listOf(TouchpadAction.Click(MouseButton.LEFT)), actions)
    }

    @Test
    fun pointerSpeedScalesMovement() {
        engine.config = engine.config.copy(pointerSpeed = 2f)
        engine.onDown(0, 0f, 0f, 0)
        engine.onMove(0, 10f, 0f, 16)
        engine.onMove(0, 20f, 0f, 32)
        assertEquals(40, actions.filterIsInstance<TouchpadAction.Move>().sumOf { it.dx })
    }

    @Test
    fun subPixelMovementAccumulates() {
        engine.config = engine.config.copy(pointerSpeed = 0.5f)
        engine.onDown(0, 0f, 0f, 0)
        engine.onMove(0, 4f, 0f, 16)
        repeat(10) { engine.onMove(0, 5f + it, 0f, 32L + it * 16) }
        // 4 + 10 px at half speed = 7 px in total, despite 0.5 px steps.
        assertEquals(7, actions.filterIsInstance<TouchpadAction.Move>().sumOf { it.dx })
    }

    @Test
    fun twoFingerDragScrolls() {
        engine.config = engine.config.copy(scrollDpPerTick = 10f, naturalScroll = true)
        engine.onDown(0, 0f, 100f, 0)
        engine.onDown(1, 50f, 100f, 0)
        for (step in 1..5) {
            engine.onMove(0, 0f, 100f + step * 10, step * 16L)
            engine.onMove(1, 50f, 100f + step * 10, step * 16L)
        }
        engine.onUp(0, 200); engine.onUp(1, 200)
        val scrolls = actions.filterIsInstance<TouchpadAction.Scroll>()
        assertEquals(5, scrolls.sumOf { it.vertical })
        assertTrue(actions.none { it is TouchpadAction.Move || it is TouchpadAction.Click })
    }

    @Test
    fun reverseScrollInvertsDirection() {
        engine.config = engine.config.copy(scrollDpPerTick = 10f, naturalScroll = false)
        engine.onDown(0, 0f, 100f, 0)
        engine.onDown(1, 50f, 100f, 0)
        engine.onMove(0, 0f, 120f, 16)
        engine.onMove(1, 50f, 120f, 16)
        assertEquals(-2, actions.filterIsInstance<TouchpadAction.Scroll>().sumOf { it.vertical })
    }

    @Test
    fun longPressThenMoveDrags() {
        engine.onDown(0, 0f, 0f, 0)
        assertFalse(engine.onLongPressTimeout(200))
        assertTrue(engine.onLongPressTimeout(500))
        assertTrue(engine.isDragging)
        engine.onMove(0, 30f, 0f, 520)
        engine.onUp(0, 600)
        assertEquals(TouchpadAction.Press(MouseButton.LEFT), actions.first())
        assertTrue(actions.any { it is TouchpadAction.Move })
        assertEquals(TouchpadAction.Release(MouseButton.LEFT), actions.last())
        assertFalse(engine.isDragging)
    }

    @Test
    fun longPressAfterMovingDoesNotDrag() {
        engine.onDown(0, 0f, 0f, 0)
        engine.onMove(0, 40f, 0f, 100)
        assertFalse(engine.onLongPressTimeout(500))
    }

    @Test
    fun cancelReleasesDrag() {
        engine.onDown(0, 0f, 0f, 0)
        engine.onLongPressTimeout(500)
        engine.onCancel()
        assertEquals(TouchpadAction.Release(MouseButton.LEFT), actions.last())
    }

    @Test
    fun accelerationIsMonotonic() {
        val c = TouchpadConfig(acceleration = true)
        assertTrue(PointerMath.gain(c, 0.1f) < PointerMath.gain(c, 1f))
        assertTrue(PointerMath.gain(c, 1f) < PointerMath.gain(c, 3f))
        assertEquals(1.5f, PointerMath.gain(c.copy(acceleration = false, pointerSpeed = 1.5f), 5f))
    }

    @Test
    fun splitChunks() {
        assertEquals(emptyList<Int>(), PointerMath.split(0))
        assertEquals(listOf(127, 127, 46), PointerMath.split(300))
        assertEquals(listOf(-127, -3), PointerMath.split(-130))
    }
}
