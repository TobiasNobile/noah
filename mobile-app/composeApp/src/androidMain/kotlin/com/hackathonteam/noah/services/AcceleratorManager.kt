package com.hackathonteam.noah.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

object AcceleratorManager : SensorEventListener{
    private var sensorManager : SensorManager? = null
    private var sensor : Sensor? = null

    fun startListening(context: Context){
        this.sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        this.sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        this.sensorManager?.registerListener(this, this.sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stopListening(){
        this.sensorManager = null
        this.sensor = null
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if(event?.sensor?.type == Sensor.TYPE_ACCELEROMETER){
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            Log.d("AcceleratorManager", "Got new values: x: $x, y: $y, z: $z")
        }

    }

}