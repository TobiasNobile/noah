package com.hackathonteam.noah.services.sensor

import android.location.LocationListener
import android.location.LocationManager

object GpsSensor : LocationSensorStrategy {
    override val window = SlidingWindowBuffer(windowMs = 5_000L)
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    override fun startListening(locationManager: LocationManager) {
        this.locationManager = locationManager
        this.locationListener = LocationListener { location ->
            val reading = SensorReading(
                timestampMs = System.currentTimeMillis(),
                x = location.latitude.toFloat(),
                y = location.longitude.toFloat(),
                z = location.altitude.toFloat()
            )
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