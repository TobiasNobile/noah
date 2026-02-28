package com.hackathonteam.noah.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.LocationManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import com.hackathonteam.noah.services.sensor.hardware.AccelerometerSensor
import com.hackathonteam.noah.services.sensor.location.GpsSensor
import com.hackathonteam.noah.services.sensor.hardware.GyroscopeSensor
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
                    is LocationSensorStrategy  -> {
                        if (ActivityCompat.checkSelfPermission(context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            sensor.startListening(lm)
                        } else {
                            Log.e("TrackingManager", "Location permissions not granted. Cannot start GPS sensor.")
                        }
                    }
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
