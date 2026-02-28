package com.hackathonteam.noah.services.sensor.location

import android.Manifest
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.annotation.RequiresPermission
import com.hackathonteam.noah.config.gpsWindowMs
import com.hackathonteam.noah.services.sensor.HardwareSensorReading
import com.hackathonteam.noah.services.sensor.LocationSensorStrategy
import com.hackathonteam.noah.services.sensor.SlidingWindowBuffer

object GpsSensor : LocationSensorStrategy {
    override val window = SlidingWindowBuffer(windowMs = gpsWindowMs)
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private val locationUpdateIntervalMs: Long = 1000L // 1 second
    private var minimumDistanceMeters: Float = 1f // 1 meter


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
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
        this.locationManager?.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            locationUpdateIntervalMs,
            minimumDistanceMeters,
            locationListener!!
        )
    }

    override fun stopListening() {
        locationManager?.removeUpdates(locationListener!!)
        locationManager = null
        locationListener = null
        window.clear()
    }
}