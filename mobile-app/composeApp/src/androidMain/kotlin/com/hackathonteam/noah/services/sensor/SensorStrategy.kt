package com.hackathonteam.noah.services.sensor

import android.hardware.SensorManager

interface SensorStrategy {
    val window: SlidingWindowBuffer
    fun startListening(sensorManager: SensorManager)
    fun stopListening()
}