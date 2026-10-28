package com.droidpad.app.hid

/** Exponential back-off for automatic reconnection to the last host. */
class ReconnectPolicy(
    private val initialDelayMs: Long = 2_000,
    private val maxDelayMs: Long = 30_000,
    val maxAttempts: Int = 8,
) {
    var attempt: Int = 0
        private set

    /** Delay before the next attempt, or null when attempts are exhausted. */
    fun nextDelayMs(): Long? {
        if (attempt >= maxAttempts) return null
        val delay = (initialDelayMs shl attempt.coerceAtMost(20)).coerceAtMost(maxDelayMs)
        attempt++
        return delay
    }

    fun reset() {
        attempt = 0
    }
}
