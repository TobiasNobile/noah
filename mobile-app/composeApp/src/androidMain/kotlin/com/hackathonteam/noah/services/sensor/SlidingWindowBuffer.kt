package com.hackathonteam.noah.services.sensor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Thread-safe sliding-window buffer that keeps only [SensorReading] (time,x,y,z)  entries
 * whose [SensorReading.timestampMs] falls within the last [windowMs] milliseconds.
 *
 * Exposes [readings] as a [StateFlow] so Compose UIs can observe live updates
 * without polling.
 *
 * @param windowMs  Width of the sliding window in milliseconds (default = 3 000 ms).
 */
class SlidingWindowBuffer(private val windowMs: Long = 3_000L) {

    private val deque = ArrayDeque<SensorReading>()

    /** Hot flow that emits a fresh snapshot after every [add] or [clear]. */
    private val _readings = MutableStateFlow<List<SensorReading>>(emptyList())
    val readings: StateFlow<List<SensorReading>> = _readings.asStateFlow()

    /**
     * Add a new reading and evict any readings that have fallen outside the window.
     */
    @Synchronized
    fun add(reading: SensorReading) {
        deque.addLast(reading)
        evictOld(reading.timestampMs)
        _readings.value = deque.toList()
    }

    /**
     * Returns an immutable snapshot of all readings currently inside the window.
     */
    @Synchronized
    fun snapshot(): List<SensorReading> {
        evictOld(System.currentTimeMillis())
        return deque.toList()
    }

    /**
     * Clears all stored readings (e.g. when tracking stops).
     */
    @Synchronized
    fun clear() {
        deque.clear()
        _readings.value = emptyList()
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun evictOld(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (deque.isNotEmpty() && deque.peekFirst()!!.timestampMs < cutoff) {
            deque.pollFirst()
        }
    }
}

