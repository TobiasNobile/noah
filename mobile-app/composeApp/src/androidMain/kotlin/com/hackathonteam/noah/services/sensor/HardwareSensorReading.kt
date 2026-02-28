package com.hackathonteam.noah.services.sensor

import kotlin.math.sqrt

/**
 * A single timestamped sample from a 3-axis sensor.
 *
 * @param timestampMs  time in milliseconds.
 * @param x            X-axis (m/s² for accelerometer, rad/s for gyroscope).
 * @param y            Y-axis.
 * @param z            Z-axis.
 */
data class HardwareSensorReading(
    val timestampMs: Long,
    val x: Float,
    val y: Float,
    val z: Float
) {
    /** Euclidean magnitude of the 3-axis vector. */
    val magnitude: Float
        get() = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
}

