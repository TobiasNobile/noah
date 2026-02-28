package com.hackathonteam.noah.tracking

import android.util.Log
import com.hackathonteam.noah.services.sensor.SensorReading

/**
 * Classifies the user's current activity from two 5-second sliding windows.
 *
 * ## Feature extraction
 *
 * Gyro => rotation rate in rad/s.
 * Accelerometer => acceleration in m/s² (including gravity).
 *      Low variance = idle or in a vehicle (because smartphone not moving)
 *      Medium var. = walking
 *      High var. = running
 *
 *
 * ## Decision thresholds (tuneable constants at the bottom of the file)
 *
 * ```
 * IDLE        accel variance < IDLE_VARIANCE_THRESHOLD
 * WALKING     IDLE_VARIANCE_THRESHOLD ≤ variance < RUNNING_VARIANCE_THRESHOLD
 *             AND peak count in [WALKING_MIN_PEAKS, WALKING_MAX_PEAKS]
 * RUNNING     variance ≥ RUNNING_VARIANCE_THRESHOLD
 *             OR peak count > WALKING_MAX_PEAKS
 * AUTOMOTIVE  variance < WALKING_VARIANCE_THRESHOLD but gyro sustained > GYRO_AUTOMOTIVE_THRESHOLD
 * ```
 */
class ActivityClassifier(
    private val idleVarianceThreshold: Float       = IDLE_VARIANCE_THRESHOLD,
    private val walkingVarianceThreshold: Float    = WALKING_VARIANCE_THRESHOLD,
    private val runningVarianceThreshold: Float    = RUNNING_VARIANCE_THRESHOLD,
    private val walkingMinPeaks: Int               = WALKING_MIN_PEAKS,
    private val walkingMaxPeaks: Int               = WALKING_MAX_PEAKS,
    private val gyroAutomotiveThreshold: Float     = GYRO_AUTOMOTIVE_THRESHOLD
) {

    /**
     * Classify activity from snapshots of both sensor windows.
     *
     * @param accelWindow  Current 5-second snapshot from AccelerometerSensor.window.
     * @param gyroWindow   Current 5-second snapshot from GyroscopeSensor.window.
     * @return             The most probable [TrackingState].
     */
    fun classify(
        accelWindow: List<SensorReading>,
        gyroWindow: List<SensorReading>
    ): TrackingState {
        if (accelWindow.isEmpty()) return TrackingState.IDLE

        // The magnitude is the euclidian magnitude of the 3d vector
        val magnitudes = accelWindow.map { it.magnitude }

        val variance = variance(magnitudes)
        val peakCount = countPeaks(magnitudes)
        val gyroMean = if (gyroWindow.isNotEmpty())
            gyroWindow.map { it.magnitude }.average().toFloat()
        else 0f

        Log.d("ActivityClassifier", "variance=$variance peakCount=$peakCount gyroMean=$gyroMean")
        return when {
            // Not moving at all
            variance < idleVarianceThreshold -> TrackingState.IDLE

            // Low-frequency oscillations + sustained gyro → vehicle
            variance < walkingVarianceThreshold &&
                    gyroMean > gyroAutomotiveThreshold -> TrackingState.AUTOMOTIVE

            // High variance or very frequent impacts → running
            variance >= runningVarianceThreshold ||
                    peakCount > walkingMaxPeaks -> TrackingState.RUNNING

            // Medium variance with footstep-frequency peaks → walking
            peakCount >= walkingMinPeaks -> TrackingState.WALKING

            else -> TrackingState.IDLE
        }
    }

    // ── feature helpers ────────────────────────────────────────────────────────

    private fun variance(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        return values.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    /**
     * Counts local maxima in [values] that are above [peakThreshold].
     * A local maximum at index i satisfies:  values\[i-1\] < values\[i\] > values\[i+1\]
     */
    private fun countPeaks(
        values: List<Float>,
        peakThreshold: Float = PEAK_THRESHOLD
    ): Int {
        if (values.size < 3) return 0
        var count = 0
        for (i in 1 until values.size - 1) {
            if (values[i] > peakThreshold &&
                values[i] > values[i - 1] &&
                values[i] > values[i + 1]
            ) count++
        }
        return count
    }

    // ── default thresholds ─────────────────────────────────────────────────────

    companion object {
        /** Accel magnitude variance below which we consider the user idle (m/s²). */
        const val IDLE_VARIANCE_THRESHOLD: Float = 0.5f

        /**
         * Variance between [IDLE_VARIANCE_THRESHOLD] and this suggests walking
         */
        const val WALKING_VARIANCE_THRESHOLD: Float = 2.5f

        /** Variance above this → running (>= ~12 m/s²). */
        const val RUNNING_VARIANCE_THRESHOLD: Float = 12f

        /**
         * Minimum number of peaks in the 5-second window to be classified as walking
         */
        const val WALKING_MIN_PEAKS: Int = 4

        /**
         * Peak count above this suggests running cadence
         */
        const val WALKING_MAX_PEAKS: Int = 15

        /** Accel magnitude threshold for a sample to be counted as a peak (m/s²). */
        const val PEAK_THRESHOLD: Float = 10.5f

        /**
         * Mean gyroscope magnitude (rad/s) above which we consider the user to be in a vehicle, even if accel variance is low.
         */
        const val GYRO_AUTOMOTIVE_THRESHOLD: Float = 0.3f
    }
}




