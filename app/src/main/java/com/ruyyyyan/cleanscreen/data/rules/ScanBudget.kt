package com.ruyyyyan.cleanscreen.data.rules

/** Bounds work across all rule traversals in one scan, including the generic fallback. */
class ScanBudget(
    private val maxNodes: Int = 400,
    private val maxDurationNanos: Long = 80_000_000L,
    private val clock: () -> Long = System::nanoTime
) {
    private val startedAt = clock()
    private var visited = 0

    fun hasRemaining(): Boolean = visited < maxNodes && clock() - startedAt < maxDurationNanos

    fun visit(): Boolean {
        if (!hasRemaining()) return false
        visited++
        return true
    }
}
