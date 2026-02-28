package com.hackathonteam.noah.tracking

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object TrackingManager {

    private var state: TrackingState = TrackingState.IDLE
    var isTrackingActive by mutableStateOf(false)

    fun getState(): TrackingState {
        return state
    }

    fun setState(state: TrackingState) {
        this.state = state
    }

    fun setTracking(active: Boolean) {
        Log.d("TrackingManager", "setTracking: $active")
        isTrackingActive = active
    }

}
