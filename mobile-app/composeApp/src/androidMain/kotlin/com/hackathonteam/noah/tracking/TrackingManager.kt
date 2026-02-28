package com.hackathonteam.noah.tracking

class TrackingManager {

    private var instance: TrackingManager ? = null;
    private var state: TrackingState = TrackingState.IDLE;

    fun getInstance(): TrackingManager {
        if(instance == null){
            instance = TrackingManager();
        }
        return instance!!;
    }

    fun getState(): TrackingState {
        return state;
    }

    fun setState(state: TrackingState) {
        this.state = state;
    }
}