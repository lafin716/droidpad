package com.droidpad.app.hid

import com.droidpad.app.input.touchpad.PointerMath
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Sends a raw report; returns false when no host is connected or the stack rejected it. */
fun interface ReportTransport {
    fun send(reportId: Int, data: ByteArray): Boolean
}

/**
 * Serialises all HID reports on one background thread so binder calls never block the UI, and
 * coalesces pointer movement so a burst of touch events becomes a few reports.
 */
class HidReportSender(
    private val transport: ReportTransport,
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hid-sender").apply { isDaemon = true }
    },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val keyGapMs: Long = 4,
) {
    private val lock = Any()
    private var pendingDx = 0
    private var pendingDy = 0
    private var moveScheduled = false

    /** Only touched on the executor thread. */
    private var buttons = 0
    /** Keys currently held down; touched only on the executor thread. */
    private val held = ArrayList<KeyStroke>()

    fun move(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        synchronized(lock) {
            pendingDx += dx
            pendingDy += dy
            if (moveScheduled) return
            moveScheduled = true
        }
        executor.execute(::flushMove)
    }

    fun scroll(vertical: Int, horizontal: Int) = executor.execute {
        val v = PointerMath.split(vertical)
        val h = PointerMath.split(horizontal)
        for (i in 0 until maxOf(v.size, h.size)) {
            mouse(0, 0, v.getOrElse(i) { 0 }, h.getOrElse(i) { 0 })
        }
    }

    fun click(button: Int, count: Int = 1) = executor.execute {
        repeat(count) {
            buttons = buttons or button
            mouse(0, 0)
            sleeper(keyGapMs * 3)
            buttons = buttons and button.inv()
            mouse(0, 0)
            if (count > 1) sleeper(keyGapMs * 10)
        }
    }

    fun press(button: Int) = executor.execute {
        buttons = buttons or button
        mouse(0, 0)
    }

    fun release(button: Int) = executor.execute {
        buttons = buttons and button.inv()
        mouse(0, 0)
    }

    /** Types each stroke as press + release. */
    fun type(strokes: List<KeyStroke>) {
        if (strokes.isEmpty()) return
        executor.execute {
            for (s in strokes) {
                keyboard(s)
                sleeper(keyGapMs)
                keyboard(null)
                sleeper(keyGapMs)
            }
            if (held.isNotEmpty()) sendHeld()
        }
    }

    /** Holds a key down until [keyUp]; the host generates auto-repeat itself. */
    fun keyDown(stroke: KeyStroke) = executor.execute {
        if (held.size >= MAX_HELD) held.removeAt(0)
        held += stroke
        sendHeld()
    }

    /** Releases [stroke], or every held key when null. */
    fun keyUp(stroke: KeyStroke? = null) = executor.execute {
        if (stroke == null) held.clear() else held.remove(stroke)
        sendHeld()
    }

    /** Releases every key and button, e.g. before disconnecting. */
    fun releaseAll() = executor.execute {
        held.clear()
        buttons = 0
        keyboard(null)
        mouse(0, 0)
    }

    private fun flushMove() {
        val dx: Int
        val dy: Int
        synchronized(lock) {
            dx = pendingDx; dy = pendingDy
            pendingDx = 0; pendingDy = 0
            moveScheduled = false
        }
        val xs = PointerMath.split(dx)
        val ys = PointerMath.split(dy)
        for (i in 0 until maxOf(xs.size, ys.size)) mouse(xs.getOrElse(i) { 0 }, ys.getOrElse(i) { 0 })
    }

    private fun mouse(dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) {
        transport.send(HidDescriptor.REPORT_ID_MOUSE, HidReports.mouse(buttons, dx, dy, wheel, pan))
    }

    private fun sendHeld() {
        if (held.isEmpty()) return keyboard(null)
        val mods = held.fold(0) { m, s -> m or s.modifiers }
        val usages = held.map { it.usage }.filter { it != 0 }.distinct().take(MAX_HELD)
        transport.send(HidDescriptor.REPORT_ID_KEYBOARD, HidReports.keyboard(mods, *usages.toIntArray()))
    }

    private fun keyboard(stroke: KeyStroke?) {
        val report = when {
            stroke == null -> HidReports.keyboardRelease()
            stroke.usage == 0 -> HidReports.keyboard(stroke.modifiers)
            else -> HidReports.keyboard(stroke.modifiers, stroke.usage)
        }
        transport.send(HidDescriptor.REPORT_ID_KEYBOARD, report)
    }

    companion object {
        /** A boot keyboard report carries at most six simultaneous keys. */
        const val MAX_HELD = 6
    }
}
