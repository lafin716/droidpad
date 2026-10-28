package com.droidpad.app.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun backsOffExponentiallyUpToMaxThenGivesUp() {
        val p = ReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 5_000, maxAttempts = 5)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), List(5) { p.nextDelayMs() })
        assertNull(p.nextDelayMs())
        assertEquals(5, p.attempt)
    }

    @Test
    fun resetStartsOver() {
        val p = ReconnectPolicy(initialDelayMs = 1_000, maxAttempts = 2)
        p.nextDelayMs(); p.nextDelayMs()
        p.reset()
        assertEquals(0, p.attempt)
        assertEquals(1_000L, p.nextDelayMs())
    }
}
