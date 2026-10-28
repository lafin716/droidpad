package com.droidpad.app.input.touchpad

import com.droidpad.app.hid.MouseButton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.truncate

/** Actions produced by the touchpad, consumed by the HID sender. */
sealed interface TouchpadAction {
    data class Move(val dx: Int, val dy: Int) : TouchpadAction
    data class Click(val button: Int) : TouchpadAction
    data class Press(val button: Int) : TouchpadAction
    data class Release(val button: Int) : TouchpadAction
    /** Positive [vertical] scrolls up, positive [horizontal] scrolls right (HID convention). */
    data class Scroll(val vertical: Int, val horizontal: Int) : TouchpadAction
}

data class TouchpadConfig(
    /** Screen density (px per dp); all thresholds below are in dp. */
    val density: Float = 1f,
    val pointerSpeed: Float = 1.0f,
    val acceleration: Boolean = true,
    val scrollSpeed: Float = 1.0f,
    val naturalScroll: Boolean = true,
    val tapToClick: Boolean = true,
    val tapTimeoutMs: Long = 220,
    val twoFingerTapTimeoutMs: Long = 300,
    val longPressMs: Long = 450,
    val tapSlopDp: Float = 10f,
    val moveThresholdDp: Float = 3f,
    val scrollDpPerTick: Float = 18f,
)

/**
 * Pure gesture state machine: one finger moves, tap = left click, two-finger tap = right click,
 * three-finger tap = middle click, two-finger drag = scroll, long-press then move = drag.
 * A double tap is two quick taps, which the host interprets as a double click.
 */
class TouchpadGestureEngine(
    var config: TouchpadConfig = TouchpadConfig(),
    private val emit: (TouchpadAction) -> Unit,
) {
    private class Pointer(val startX: Float, val startY: Float, var x: Float, var y: Float, var t: Long)

    private val pointers = LinkedHashMap<Long, Pointer>()
    private var gestureStart = 0L
    private var maxPointers = 0
    private var exceededSlop = false
    private var moving = false
    private var dragging = false
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var remX = 0f
    private var remY = 0f
    private var scrollRemV = 0f
    private var scrollRemH = 0f

    val isDragging: Boolean get() = dragging

    fun onDown(id: Long, x: Float, y: Float, timeMs: Long) {
        if (pointers.isEmpty()) {
            gestureStart = timeMs
            maxPointers = 0
            exceededSlop = false
            moving = false
            pendingDx = 0f; pendingDy = 0f
            remX = 0f; remY = 0f
            scrollRemV = 0f; scrollRemH = 0f
        }
        pointers[id] = Pointer(x, y, x, y, timeMs)
        maxPointers = maxOf(maxPointers, pointers.size)
    }

    fun onMove(id: Long, x: Float, y: Float, timeMs: Long) {
        val p = pointers[id] ?: return
        val dx = x - p.x
        val dy = y - p.y
        val dt = (timeMs - p.t).coerceAtLeast(1)
        p.x = x; p.y = y; p.t = timeMs
        if (hypot(x - p.startX, y - p.startY) > config.tapSlopDp * config.density) exceededSlop = true

        when {
            dragging || (maxPointers == 1 && pointers.size == 1) -> pointerMove(dx, dy, dt)
            pointers.size >= 2 && !dragging -> {
                // Average the contribution of each finger so two fingers scroll at finger speed.
                scroll(dx / pointers.size, dy / pointers.size)
            }
        }
    }

    fun onUp(id: Long, timeMs: Long) {
        pointers.remove(id) ?: return
        if (pointers.isNotEmpty()) return
        val duration = timeMs - gestureStart
        when {
            dragging -> {
                dragging = false
                emit(TouchpadAction.Release(MouseButton.LEFT))
            }
            exceededSlop || !config.tapToClick -> Unit
            maxPointers == 1 && duration <= config.tapTimeoutMs -> emit(TouchpadAction.Click(MouseButton.LEFT))
            maxPointers == 2 && duration <= config.twoFingerTapTimeoutMs -> emit(TouchpadAction.Click(MouseButton.RIGHT))
            maxPointers >= 3 && duration <= config.twoFingerTapTimeoutMs -> emit(TouchpadAction.Click(MouseButton.MIDDLE))
        }
    }

    fun onCancel() {
        pointers.clear()
        if (dragging) {
            dragging = false
            emit(TouchpadAction.Release(MouseButton.LEFT))
        }
    }

    /**
     * Called by the UI [TouchpadConfig.longPressMs] after the first finger went down.
     * @return true when a drag started (the UI can give haptic feedback).
     */
    fun onLongPressTimeout(timeMs: Long): Boolean {
        if (pointers.size != 1 || maxPointers != 1 || exceededSlop || moving || dragging) return false
        if (timeMs - gestureStart < config.longPressMs) return false
        dragging = true
        moving = true
        emit(TouchpadAction.Press(MouseButton.LEFT))
        return true
    }

    private fun pointerMove(dx: Float, dy: Float, dtMs: Long) {
        if (!moving) {
            pendingDx += dx; pendingDy += dy
            if (hypot(pendingDx, pendingDy) < config.moveThresholdDp * config.density) return
            moving = true
            emitMove(pendingDx, pendingDy, dtMs)
            pendingDx = 0f; pendingDy = 0f
            return
        }
        emitMove(dx, dy, dtMs)
    }

    private fun emitMove(dx: Float, dy: Float, dtMs: Long) {
        val gain = PointerMath.gain(config, hypot(dx, dy) / config.density / dtMs)
        remX += dx * gain
        remY += dy * gain
        val outX = truncate(remX).toInt()
        val outY = truncate(remY).toInt()
        remX -= outX; remY -= outY
        if (outX != 0 || outY != 0) emit(TouchpadAction.Move(outX, outY))
    }

    private fun scroll(dx: Float, dy: Float) {
        val step = config.scrollDpPerTick * config.density / config.scrollSpeed.coerceAtLeast(0.1f)
        val sign = if (config.naturalScroll) 1f else -1f
        // Lock to the dominant axis to avoid accidental diagonal scrolling.
        if (abs(dy) >= abs(dx)) scrollRemV += sign * dy / step else scrollRemH += -sign * dx / step
        val v = truncate(scrollRemV).toInt()
        val h = truncate(scrollRemH).toInt()
        scrollRemV -= v; scrollRemH -= h
        if (v != 0 || h != 0) emit(TouchpadAction.Scroll(v, h))
    }
}

object PointerMath {
    /**
     * Pointer gain for a finger speed in dp/ms. Slow movements stay precise; fast flicks travel
     * further so the whole host screen is reachable from the smaller touchpad.
     */
    fun gain(config: TouchpadConfig, speedDpPerMs: Float): Float {
        val base = config.pointerSpeed
        if (!config.acceleration) return base
        val accel = (0.7f + 0.9f * speedDpPerMs).coerceIn(0.7f, 3.0f)
        return base * accel
    }

    /** Splits a large relative value into chunks that fit a signed 8-bit HID field. */
    fun split(value: Int, limit: Int = 127): List<Int> {
        if (value == 0) return emptyList()
        val out = ArrayList<Int>()
        var rest = value
        while (rest != 0) {
            val chunk = rest.coerceIn(-limit, limit)
            out += chunk
            rest -= chunk
        }
        return out
    }
}
