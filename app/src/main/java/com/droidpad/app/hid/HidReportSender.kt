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
 *
 * Movement is paced to at most one report per [moveIntervalMs]. `sendReport` returns as soon as
 * the report is queued in the Bluetooth stack, so sending faster than the link drains (one report
 * per connection/sniff interval) only grows that queue and the pointer trails the finger further
 * and further. Pacing keeps the queue short; the movement made meanwhile is summed, not dropped.
 *
 * Input arrives at its own rate (touch ~8 ms, gyro 5 ms) which beats against the report slots, so
 * plain summing puts one input's worth of motion in some reports and two in others; at speed that
 * reads as stutter. With [smoothingMs] > 0 each input's motion is spread over the time since the
 * previous input and every report carries the motion up to `now - smoothingMs`, giving even steps
 * for the price of that much delay. Buttons and scrolling flush pending motion first.
 */
class HidReportSender(
    private val transport: ReportTransport,
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hid-sender").apply { isDaemon = true }
    },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val keyGapMs: Long = 4,
    moveIntervalMs: Long = MOVE_INTERVAL_MS,
    smoothingMs: Long = 0,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Minimum gap between movement reports; 0 sends every coalesced batch immediately. */
    @Volatile var moveIntervalMs: Long = moveIntervalMs
    /** Delay used to resample movement evenly onto report slots; 0 sends it as it arrives. */
    @Volatile var smoothingMs: Long = smoothingMs

    /** Motion [dx], [dy] made evenly between [start] and [end]; [taken] is the fraction sent so far. */
    private class Segment(val start: Long, val end: Long, val dx: Float, val dy: Float) {
        var taken = 0f
    }

    private val lock = Any()
    /** Guarded by [lock]. */
    private val segments = ArrayDeque<Segment>()
    private var lastInputAt = Long.MIN_VALUE / 2
    private var moveScheduled = false
    /** Time of the last movement tick; only touched on the executor thread. */
    private var lastMoveAt = Long.MIN_VALUE / 2
    /** Sub-count motion carried to the next report; only touched on the executor thread. */
    private var remX = 0f
    private var remY = 0f

    /** Only touched on the executor thread. */
    private var buttons = 0
    /** Keys currently held down; touched only on the executor thread. */
    private val held = ArrayList<KeyStroke>()

    fun move(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        synchronized(lock) {
            val now = clock()
            // Spread over the gap since the previous input; after a pause it lands at once.
            val start = if (now - lastInputAt in 0..MAX_SEGMENT_MS) lastInputAt else now
            lastInputAt = now
            segments.addLast(Segment(start, now, dx.toFloat(), dy.toFloat()))
            if (moveScheduled) return
            moveScheduled = true
        }
        executor.execute(::flushMove)
    }

    fun scroll(vertical: Int, horizontal: Int) = executor.execute {
        flushAllMotion()
        val v = PointerMath.split(vertical)
        val h = PointerMath.split(horizontal)
        for (i in 0 until maxOf(v.size, h.size)) {
            mouse(0, 0, v.getOrElse(i) { 0 }, h.getOrElse(i) { 0 })
        }
    }

    fun click(button: Int, count: Int = 1) = executor.execute {
        flushAllMotion()
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
        flushAllMotion()
        buttons = buttons or button
        mouse(0, 0)
    }

    fun release(button: Int) = executor.execute {
        flushAllMotion()
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
        val interval = moveIntervalMs
        // Smoothing needs paced ticks; unpaced mode sends everything as it arrives.
        val delay = if (interval > 0) smoothingMs.coerceAtLeast(0) else 0
        if (interval > 0) {
            // Wait for the next slot (and for the oldest motion to come due) while further
            // movement keeps accumulating.
            val oldest = synchronized(lock) { segments.firstOrNull()?.start } ?: 0
            val due = if (delay > 0) oldest + delay + 1 else Long.MIN_VALUE
            val wait = maxOf(lastMoveAt + interval, due) - clock()
            if (wait > 0) sleeper(wait)
        }
        val now = clock()
        val more = synchronized(lock) {
            takeMotion(now - delay)
            segments.isNotEmpty().also { if (!it) moveScheduled = false }
        }
        sendMotion()
        lastMoveAt = now
        // Re-post instead of looping so key and button reports can interleave.
        if (more) executor.execute(::flushMove)
    }

    /** Sends all buffered motion now, e.g. so a click lands where the pointer was headed. */
    private fun flushAllMotion() {
        synchronized(lock) { takeMotion(Long.MAX_VALUE) }
        sendMotion()
    }

    /** Moves the motion made before [cutoff] into [remX]/[remY]. Call with [lock] held. */
    private fun takeMotion(cutoff: Long) {
        while (segments.isNotEmpty()) {
            val s = segments.first()
            val f = when {
                cutoff >= s.end -> 1f
                cutoff <= s.start -> 0f
                else -> (cutoff - s.start).toFloat() / (s.end - s.start)
            }
            if (f > s.taken) {
                remX += s.dx * (f - s.taken)
                remY += s.dy * (f - s.taken)
                s.taken = f
            }
            if (f < 1f) break
            segments.removeFirst()
        }
    }

    private fun sendMotion() {
        val dx = Math.round(remX)
        val dy = Math.round(remY)
        if (dx == 0 && dy == 0) return
        remX -= dx; remY -= dy
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
        /** ~83 Hz, just above the 11.25 ms latency requested in the HID QoS settings. */
        const val MOVE_INTERVAL_MS = 12L
        /** About one touch frame at 120 Hz, enough to always have the next input in hand. */
        const val MOVE_SMOOTHING_MS = 8L
        /** Longest input gap still treated as continuous motion. */
        const val MAX_SEGMENT_MS = 40L
    }
}
