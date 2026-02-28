package com.hackathonteam.noah.services.sensor.hardware

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.hackathonteam.noah.services.sensor.HardwareSensorReading
import com.hackathonteam.noah.services.sensor.HardwareSensorStrategy
import com.hackathonteam.noah.services.sensor.SlidingWindowBuffer

object GyroscopeSensor : SensorEventListener, HardwareSensorStrategy {
    override val window = SlidingWindowBuffer(windowMs = 5_000L)

    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null

    override fun startListening(sensorManager: SensorManager) {
        this.sensorManager = sensorManager
        this.sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager.registerListener(this, this.sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun stopListening() {
        sensorManager?.unregisterListener(this)
        this.sensorManager = null
        this.sensor = null
        window.clear()
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val reading = HardwareSensorReading(
                timestampMs = System.currentTimeMillis(),
                x = event.values[0],
                y = event.values[1],
                z = event.values[2]
            )
            window.add(reading)
            Log.d("GyroscopeSensor", "x=${reading.x} y=${reading.y} z=${reading.z}")
        }
    }
}