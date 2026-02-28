package com.hackathonteam.noah.tracking

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hackathonteam.noah.services.AcceleratorManager

object TrackingManager {

    private var state: TrackingState = TrackingState.IDLE
    var isTrackingActive by mutableStateOf(false)


    fun getState(): TrackingState {
        return state
    }

    fun setState(state: TrackingState) {
        this.state = state
    }

    fun stopListening(){
        isTrackingActive = false
        AcceleratorManager.stopListening()
    }

    fun startListening(context: Context){
        isTrackingActive = true
        AcceleratorManager.startListening(context)
    }

}
