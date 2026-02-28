package com.hackathonteam.noah.services.sensor.location

import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import com.hackathonteam.noah.services.sensor.HardwareSensorReading
import com.hackathonteam.noah.services.sensor.LocationSensorStrategy
import com.hackathonteam.noah.services.sensor.SlidingWindowBuffer

object GpsSensor : LocationSensorStrategy {
    override val window = SlidingWindowBuffer(windowMs = 5_000L)
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    override fun startListening(locationManager: LocationManager) {
        Log.d("GpsSensor", "Starting GPS sensor")
        this.locationManager = locationManager
        this.locationListener = LocationListener { location ->
            val reading = HardwareSensorReading(
                timestampMs = System.currentTimeMillis(),
                x = location.latitude.toFloat(),
                y = location.longitude.toFloat(),
                z = location.altitude.toFloat()
            )
            Log.d("GpsSensor", "Lat=${reading.x} Lon=${reading.y} Alt=${reading.z}")
            window.add(reading)
        }
    }

    override fun stopListening() {
        locationManager?.removeUpdates(locationListener!!)
        locationManager = null
        locationListener = null
        window.clear()
    }
}