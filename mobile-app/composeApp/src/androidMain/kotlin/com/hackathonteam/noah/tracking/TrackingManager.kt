package com.hackathonteam.noah.tracking

import android.content.Context
import android.hardware.SensorManager
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hackathonteam.noah.services.sensor.AccelerometerSensor
import com.hackathonteam.noah.services.sensor.GpsSensor
import com.hackathonteam.noah.services.sensor.GyroscopeSensor
import com.hackathonteam.noah.services.sensor.HardwareSensorStrategy
import com.hackathonteam.noah.services.sensor.LocationSensorStrategy
import com.hackathonteam.noah.services.sensor.SensorStrategy

object TrackingManager {
    var isTrackingActive by mutableStateOf(false)

    var trackingState by mutableStateOf<TrackingState>(TrackingState.IDLE)
        private set

    // System services resolved once and shared with all sensors
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null

    private val sensors: List<SensorStrategy> = listOf(
        AccelerometerSensor,
        GyroscopeSensor,
        GpsSensor
    )

    private val classifier = ActivityClassifier()

    fun startListening(context: Context) {
        if (!isTrackingActive) {
            isTrackingActive = true
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            sensorManager = sm
            locationManager = lm
            sensors.forEach { sensor ->
                when (sensor) {
                    is HardwareSensorStrategy  -> sensor.startListening(sm)
                    is LocationSensorStrategy  -> sensor.startListening(lm)
                }
            }
        }
    }

    fun stopListening() {
        if (isTrackingActive) {
            isTrackingActive = false
            sensors.forEach { it.stopListening() }
            sensorManager = null
            locationManager = null
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
