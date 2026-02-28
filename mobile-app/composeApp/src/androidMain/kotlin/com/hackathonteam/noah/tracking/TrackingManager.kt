package com.hackathonteam.noah.tracking

import android.content.Context
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hackathonteam.noah.services.sensor.AccelerometerSensor
import com.hackathonteam.noah.services.sensor.GyroscopeSensor
import com.hackathonteam.noah.services.sensor.SensorStrategy

object TrackingManager {
    var isTrackingActive by mutableStateOf(false)

    var trackingState by mutableStateOf<TrackingState>(TrackingState.IDLE)
        private set

    // Single SensorManager shared by all sensors
    private var sensorManager: SensorManager? = null

    private val sensors: List<SensorStrategy> = listOf(
        AccelerometerSensor,
        GyroscopeSensor
    )

    private val classifier = ActivityClassifier()

    fun startListening(context: Context) {
        if (!isTrackingActive) {
            isTrackingActive = true
            val retrievedSm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager = retrievedSm
            sensors.forEach { it.startListening(retrievedSm) }
        }
    }

    fun stopListening() {
        if (isTrackingActive) {
            isTrackingActive = false
            sensors.forEach { it.stopListening() }
            sensorManager = null
            trackingState = TrackingState.IDLE
        }
    }

    /**
     * Called by [AccelerometerSensor] (or any sensor) after each new sample is
     * pushed to its window. Re-classifies the current activity from the latest
     * 5-second snapshots of both sensors and updates [trackingState].
     */
    internal fun onNewSample() {
        trackingState = classifier.classify(
            accelWindow = AccelerometerSensor.window.snapshot(),
            gyroWindow  = GyroscopeSensor.window.snapshot()
        )
    }
}
