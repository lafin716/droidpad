package com.droidpad.app.hid

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTransport : ReportTransport {
    val reports = ArrayList<Pair<Int, List<Int>>>()
    override fun send(reportId: Int, data: ByteArray): Boolean {
        reports += reportId to data.map { it.toInt() }
        return true
    }
    val keyboard get() = reports.filter { it.first == HidDescriptor.REPORT_ID_KEYBOARD }.map { it.second }
    val mouse get() = reports.filter { it.first == HidDescriptor.REPORT_ID_MOUSE }.map { it.second }
}

fun directSender(t: ReportTransport) = HidReportSender(t, executor = { it.run() }, sleeper = {})

class HidReportSenderTest {
    private val t = RecordingTransport()
    private val sender = directSender(t)

    @Test
    fun typeSendsPressThenRelease() {
        sender.type(listOf(KeyStroke(KeyCodes.A, Modifier.LEFT_SHIFT)))
        assertEquals(listOf(listOf(2, 0, 4, 0, 0, 0, 0, 0), List(8) { 0 }), t.keyboard)
    }

    @Test
    fun modifierOnlyStrokeSendsNoUsage() {
        sender.type(listOf(KeyStroke(0, Modifier.RIGHT_ALT)))
        assertEquals(listOf(0x40, 0, 0, 0, 0, 0, 0, 0), t.keyboard[0].map { it and 0xFF })
    }

    @Test
    fun largeMoveIsSplitIntoSignedByteChunks() {
        sender.move(300, -10)
        assertEquals(
            listOf(listOf(0, 127, -10, 0, 0), listOf(0, 127, 0, 0, 0), listOf(0, 46, 0, 0, 0)),
            t.mouse,
        )
    }

    @Test
    fun clickPressesAndReleasesButton() {
        sender.click(MouseButton.RIGHT)
        assertEquals(listOf(listOf(2, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0)), t.mouse)
    }

    @Test
    fun doubleClickSendsTwoClicks() {
        sender.click(MouseButton.LEFT, count = 2)
        assertEquals(listOf(1, 0, 1, 0), t.mouse.map { it[0] })
    }

    @Test
    fun dragKeepsButtonHeldWhileMoving() {
        sender.press(MouseButton.LEFT)
        sender.move(5, 5)
        sender.release(MouseButton.LEFT)
        assertEquals(listOf(listOf(1, 0, 0, 0, 0), listOf(1, 5, 5, 0, 0), listOf(0, 0, 0, 0, 0)), t.mouse)
    }

    @Test
    fun scrollUsesWheelAndPan() {
        sender.scroll(-2, 3)
        assertEquals(listOf(listOf(0, 0, 0, -2, 3)), t.mouse)
    }

    @Test
    fun heldKeyIsRestoredAfterTyping() {
        sender.keyDown(KeyStroke(KeyCodes.LEFT))
        sender.type(listOf(KeyStroke(KeyCodes.A)))
        assertEquals(KeyCodes.LEFT, t.keyboard.last()[2])
        sender.keyUp(KeyStroke(KeyCodes.LEFT))
        assertEquals(List(8) { 0 }, t.keyboard.last())
    }

    @Test
    fun releaseAllClearsKeysAndButtons() {
        sender.press(MouseButton.LEFT)
        sender.keyDown(KeyStroke(KeyCodes.A))
        sender.releaseAll()
        assertEquals(List(8) { 0 }, t.keyboard.last())
        assertEquals(listOf(0, 0, 0, 0, 0), t.mouse.last())
    }

    @Test
    fun concurrentHoldsAreReleasedIndependently() {
        sender.keyDown(KeyStroke(KeyCodes.LEFT))
        sender.keyDown(KeyStroke(KeyCodes.UP, Modifier.LEFT_SHIFT))
        assertEquals(listOf(Modifier.LEFT_SHIFT, 0, KeyCodes.LEFT, KeyCodes.UP, 0, 0, 0, 0), t.keyboard.last())
        sender.keyUp(KeyStroke(KeyCodes.LEFT))
        assertEquals(listOf(Modifier.LEFT_SHIFT, 0, KeyCodes.UP, 0, 0, 0, 0, 0), t.keyboard.last())
        sender.keyUp(KeyStroke(KeyCodes.UP, Modifier.LEFT_SHIFT))
        assertEquals(List(8) { 0 }, t.keyboard.last())
    }

    @Test
    fun movesArePacedAndCoalesced() {
        var now = 1_000L
        val sleeps = ArrayList<Long>()
        val queue = ArrayDeque<Runnable>()
        val paced = HidReportSender(
            t, executor = { queue += it }, sleeper = { sleeps += it; now += it },
            moveIntervalMs = 12, clock = { now },
        )
        paced.move(3, 0)
        queue.removeFirst().run()
        assertEquals(emptyList<Long>(), sleeps)

        // Two moves 4 ms later: one report after the remaining 8 ms, carrying both.
        now += 4
        paced.move(2, 1)
        paced.move(1, 1)
        assertEquals(1, queue.size)
        queue.removeFirst().run()
        assertEquals(listOf(8L), sleeps)
        assertEquals(listOf(listOf(0, 3, 0, 0, 0), listOf(0, 3, 2, 0, 0)), t.mouse)
    }

    @Test
    fun zeroIntervalSendsWithoutWaiting() {
        var now = 1_000L
        val sleeps = ArrayList<Long>()
        val queue = ArrayDeque<Runnable>()
        val paced = HidReportSender(
            t, executor = { queue += it }, sleeper = { sleeps += it; now += it },
            moveIntervalMs = 12, clock = { now },
        )
        paced.moveIntervalMs = 0
        paced.move(3, 0)
        queue.removeFirst().run()
        now += 1
        paced.move(2, 0)
        queue.removeFirst().run()
        assertEquals(emptyList<Long>(), sleeps)
        assertEquals(listOf(listOf(0, 3, 0, 0, 0), listOf(0, 2, 0, 0, 0)), t.mouse)
    }

    /** Feeds 16 counts every 8 ms (like 120 Hz touch) for [ms] and returns the x of every report. */
    private fun steadyStream(smoothingMs: Long, ms: Int = 200): List<Int> {
        var now = 1_000L
        val queue = ArrayDeque<Runnable>()
        lateinit var paced: HidReportSender
        fun tick() {
            now++
            if (now % 8 == 0L && now < 1_000L + ms) paced.move(16, 0)
        }
        paced = HidReportSender(
            t, executor = { queue += it }, sleeper = { repeat(it.toInt()) { tick() } },
            moveIntervalMs = 12, smoothingMs = smoothingMs, clock = { now },
        )
        while (now < 1_000L + ms + 50) if (queue.isNotEmpty()) queue.removeFirst().run() else tick()
        return t.mouse.map { it[1] }
    }

    @Test
    fun withoutSmoothingStepsBeatBetweenInputAndReportRate() {
        val steps = steadyStream(smoothingMs = 0)
        assertEquals(setOf(16, 32), steps.drop(2).dropLast(2).toSet())
    }

    @Test
    fun smoothingGivesEvenStepsAndKeepsTotal() {
        val steps = steadyStream(smoothingMs = 8)
        assertEquals(setOf(24), steps.drop(2).dropLast(2).toSet())
        assertEquals(24 * 16, steps.sum()) // every input (1008..1192 ms) arrives in full
    }

    @Test
    fun clickFlushesSmoothedMotionFirst() {
        var now = 1_000L
        val queue = ArrayDeque<Runnable>()
        val paced = HidReportSender(
            t, executor = { queue += it }, sleeper = { now += it },
            moveIntervalMs = 12, smoothingMs = 8, clock = { now },
        )
        paced.move(5, 0)
        now += 4
        paced.move(7, 0)
        paced.click(MouseButton.LEFT)
        // The click runs before the paced flush, but still lands after all the motion.
        queue.removeAt(1).run()
        assertEquals(listOf(listOf(0, 12, 0, 0, 0), listOf(1, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0)), t.mouse)
        queue.removeFirst().run()
        assertEquals(3, t.mouse.size)
    }
}
