package com.hackathonteam.noah.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hackathonteam.noah.services.sensor.HardwareSensorReading

@Composable
fun GPSCoordsList(
    reading: List<HardwareSensorReading>,
    modifier: Modifier = Modifier,
    windowMs: Long
) {
    Column(modifier = modifier) {
        Text(text = "GPS Coordinates List")
        Column() {
            reading.forEach { sensorReading ->
                Text(text = "Lat: ${sensorReading.x}, Lon: ${sensorReading.y}, Alt: ${sensorReading.z}")
            }
        }
    }
}