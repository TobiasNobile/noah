package com.hackathonteam.noah.services.streaming
import android.util.Log
import com.hackathonteam.noah.services.sensor.audio.MicrophoneSensor
import com.hackathonteam.noah.tracking.TrackingManager
import com.hackathonteam.noah.ui.interactions.latestCameraFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt
private const val TAG = "StreamDispatcher"
private const val PERIODIC_INTERVAL_MS = 5_000L
private const val VOICE_ACTIVITY_RMS_THRESHOLD = 800.0
object StreamDispatcher {
    private val _payloads = MutableSharedFlow<StreamPayload>(extraBufferCapacity = 16)
    /**
     * Hot stream of assembled [StreamPayload] objects, ready to be sent to the
     * FastAPI server. Collect this flow in a service or ViewModel.
     * TODO: add a collector that performs the actual HTTP / WebSocket upload.
     */

    val payloads: SharedFlow<StreamPayload> = _payloads.asSharedFlow()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val audioMutex = Mutex()
    private val pendingAudioChunks = mutableListOf<ByteArray>()
    private var audioCollectorJob: Job? = null
    private var periodicJob: Job? = null
    /**
     * Start collecting from all data sources and scheduling dispatches.
     * Safe to call multiple times — subsequent calls are no-ops.
     * Called automatically by [TrackingManager.startListening].
     */
    fun start() {
        if (audioCollectorJob?.isActive == true) return
        Log.d(TAG, "StreamDispatcher started")
        audioCollectorJob = scope.launch {
            MicrophoneSensor.audioChunks.collect { pcm ->
                audioMutex.withLock { pendingAudioChunks.add(pcm) }
                if (isVoiceActivity(pcm)) {
                    Log.d(TAG, "Voice activity detected — dispatching immediately")
                    dispatch(triggeredByVoice = true)
                }
            }
        }
        periodicJob = scope.launch {
            while (true) {
                delay(PERIODIC_INTERVAL_MS)
                dispatch(triggeredByVoice = false)
            }
        }
    }
    /**
     * Stop all collection and cancel pending periodic dispatches.
     * Called automatically by [TrackingManager.stopListening].
     */
    fun stop() {
        audioCollectorJob?.cancel()
        periodicJob?.cancel()
        audioCollectorJob = null
        periodicJob = null
        scope.launch { audioMutex.withLock { pendingAudioChunks.clear() } }
        Log.d(TAG, "StreamDispatcher stopped")
    }
    private suspend fun dispatch(triggeredByVoice: Boolean) {
        val chunks: List<ByteArray> = audioMutex.withLock {
            val snapshot = pendingAudioChunks.toList()
            pendingAudioChunks.clear()
            snapshot
        }
        val payload = StreamPayload(
            timestampMs      = System.currentTimeMillis(),
            trackingState    = TrackingManager.trackingState,
            latestFrame      = latestCameraFrame.value,
            audioChunks      = chunks,
            triggeredByVoice = triggeredByVoice,
        )
        Log.d(TAG, "Dispatching payload -> $payload")
        _payloads.emit(payload)
    }
    /**
     * Energy-based Voice Activity Detection (VAD).
     * Computes RMS of PCM-16 little-endian samples.
     * Returns true when RMS exceeds [VOICE_ACTIVITY_RMS_THRESHOLD].
     */
    private fun isVoiceActivity(pcm: ByteArray): Boolean {
        if (pcm.size < 2) return false
        val sampleCount = pcm.size / 2
        var sumOfSquares = 0.0
        for (i in 0 until sampleCount) {
            val sample = (pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)
            sumOfSquares += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sumOfSquares / sampleCount)
        return rms > VOICE_ACTIVITY_RMS_THRESHOLD
    }
}
