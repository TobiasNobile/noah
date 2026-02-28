package com.hackathonteam.noah.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hackathonteam.noah.config.accelerometerAndGyroscopeWindowMs
import com.hackathonteam.noah.config.gpsWindowMs
import com.hackathonteam.noah.services.sensor.hardware.AccelerometerSensor
import com.hackathonteam.noah.services.sensor.location.GpsSensor
import com.hackathonteam.noah.tracking.TrackingManager

@Composable
@Preview
fun App() {
    val context: Context = LocalContext.current

    // Collect the live sliding-window readings as Compose state.
    // Every time AccelerometerSensor pushes a new reading the chart recomposes.
    val accelReadings by AccelerometerSensor.window.readings.collectAsState()
    val gpsReading by GpsSensor.window.readings.collectAsState()

    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = {
                if (TrackingManager.isTrackingActive) {
                    TrackingManager.stopListening()
                } else {
                    TrackingManager.startListening(context)
                }
            }) {
                Text(if (TrackingManager.isTrackingActive) "Stop tracking" else "Start tracking")
            }

            if (TrackingManager.isTrackingActive) {
                Text(
                    text = "Activity: ${TrackingManager.trackingState.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                MagnitudeChart(
                    readings  = accelReadings,
                    modifier  = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    windowMs  = accelerometerAndGyroscopeWindowMs,
                )

                GPSCoordsList(
                    reading = gpsReading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    windowMs = gpsWindowMs
                )
            }
        }
    }
}