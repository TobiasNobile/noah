package com.hackathonteam.noah.services.sensor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Thread-safe sliding-window buffer that keeps only [HardwareSensorReading] (time,x,y,z)  entries
 * whose [HardwareSensorReading.timestampMs] falls within the last [windowMs] milliseconds.
 *
 * Exposes [readings] as a [StateFlow] so Compose UIs can observe live updates
 * without polling.
 *
 * @param windowMs  Width of the sliding window in milliseconds
 */
class SlidingWindowBuffer(private val windowMs : Long) {

    private val deque = ArrayDeque<HardwareSensorReading>()

    /** Hot flow that emits a fresh snapshot after every [add] or [clear]. */
    private val _readings = MutableStateFlow<List<HardwareSensorReading>>(emptyList())
    val readings: StateFlow<List<HardwareSensorReading>> = _readings.asStateFlow()

    /**
     * Add a new reading and evict any readings that have fallen outside the window.
     */
    @Synchronized
    fun add(reading: HardwareSensorReading) {
        deque.addLast(reading)
        evictOld(reading.timestampMs)
        _readings.value = deque.toList()
    }

    /**
     * Returns an immutable snapshot of all readings currently inside the window.
     */
    @Synchronized
    fun snapshot(): List<HardwareSensorReading> {
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

    fun getLatest(): HardwareSensorReading? {
        return deque.peekLast()
    }
}

