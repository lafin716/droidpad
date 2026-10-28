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
}
