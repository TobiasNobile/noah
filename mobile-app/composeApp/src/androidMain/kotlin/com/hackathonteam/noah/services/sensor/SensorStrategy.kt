package com.hackathonteam.noah.services.sensor

import android.content.Context
import android.hardware.SensorManager
import android.location.LocationManager

interface SensorStrategy {
    val window: SlidingWindowBuffer
    fun stopListening()
}

/** accelerometer, gyroscope… */
interface HardwareSensorStrategy : SensorStrategy {
    fun startListening(sensorManager: SensorManager)
}

/** GPS */
interface LocationSensorStrategy : SensorStrategy {
    fun startListening(locationManager: LocationManager)
}

/** Microphone*/
interface AudioSensorStrategy {
    fun startListening(context: Context)
    fun stopListening()
}

