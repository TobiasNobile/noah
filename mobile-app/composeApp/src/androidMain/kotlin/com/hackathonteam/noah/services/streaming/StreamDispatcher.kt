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
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val TAG = "StreamDispatcher"
private const val PERIODIC_INTERVAL_MS = 5_000L
private const val VOICE_ACTIVITY_RMS_THRESHOLD = 800.0

object StreamDispatcher {

    // -------------------------------------------------------------------------
    // Public observable stream (kept for in-app observers / debugging)
    // -------------------------------------------------------------------------

    private val _payloads = MutableSharedFlow<StreamPayload>(extraBufferCapacity = 16)

    /**
     * Hot stream of assembled [StreamPayload] objects. Collect this flow in a
     * ViewModel or composable for in-app observation.
     * HTTP uploading is handled internally by [dispatch].
     */
    val payloads: SharedFlow<StreamPayload> = _payloads.asSharedFlow()

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val audioMutex = Mutex()
    private val pendingAudioChunks = mutableListOf<ByteArray>()

    private var audioCollectorJob: Job? = null
    private var periodicJob: Job? = null

    /**
     * Session UUID returned by the first POST /ask.
     * Null until [register] has completed successfully.
     */
    @Volatile private var sessionUuid: String? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start collecting from all data sources and scheduling dispatches.
     * On the very first call, a POST /ask is made to obtain the session UUID.
     * Safe to call multiple times — subsequent calls are no-ops until [stop].
     * Called automatically by [TrackingManager.startListening].
     */
    fun start() {
        if (audioCollectorJob?.isActive == true) return
        Log.d(TAG, "StreamDispatcher started")

        // Register with the server once per session.
        if (sessionUuid == null) {
            scope.launch(Dispatchers.IO) {
                Log.d(TAG, "Registering session via POST /ask …")
                when (val result = NoahApiClient.register()) {
                    is RegisterResult.Registered -> {
                        sessionUuid = result.uuid
                        Log.d(TAG, "Session registered — uuid=${result.uuid}")
                    }
                    is RegisterResult.Error -> {
                        Log.e(TAG, "Session registration failed: ${result.reason}")
                    }
                }
            }
        }

        // Collect audio chunks and trigger voice-activity dispatches.
        audioCollectorJob = scope.launch {
            MicrophoneSensor.audioChunks.collect { pcm ->
                audioMutex.withLock { pendingAudioChunks.add(pcm) }
                if (isVoiceActivity(pcm)) {
                    Log.d(TAG, "Voice activity detected — dispatching immediately")
                    dispatch(triggeredByVoice = true)
                }
            }
        }

        // Periodic 5-second dispatch.
        periodicJob = scope.launch {
            while (true) {
                delay(PERIODIC_INTERVAL_MS)
                dispatch(triggeredByVoice = false)
            }
        }
    }

    /**
     * Stop all collection and cancel pending periodic dispatches.
     * The session UUID is retained so that the next [start] call does **not**
     * re-register. Set [resetSession] to `true` to force a new /ask on the
     * next [start] call.
     * Called automatically by [TrackingManager.stopListening].
     */
    fun stop(resetSession: Boolean = false) {
        audioCollectorJob?.cancel()
        periodicJob?.cancel()
        audioCollectorJob = null
        periodicJob = null
        scope.launch { audioMutex.withLock { pendingAudioChunks.clear() } }
        if (resetSession) sessionUuid = null
        Log.d(TAG, "StreamDispatcher stopped (resetSession=$resetSession)")
    }

    // -------------------------------------------------------------------------
    // Dispatch logic
    // -------------------------------------------------------------------------

    private suspend fun dispatch(triggeredByVoice: Boolean) {
        val chunks: List<ByteArray> = audioMutex.withLock {
            val snapshot = pendingAudioChunks.toList()
            pendingAudioChunks.clear()
            snapshot
        }

        val frame = latestCameraFrame.value

        val payload = StreamPayload(
            timestampMs      = System.currentTimeMillis(),
            trackingState    = TrackingManager.trackingState,
            latestFrame      = frame,
            audioChunks      = chunks,
            triggeredByVoice = triggeredByVoice,
        )
        Log.d(TAG, "Dispatching payload -> $payload")
        _payloads.emit(payload)

        // Upload to the FastAPI server on an IO thread.
        val uuid = sessionUuid
        if (uuid == null) {
            Log.w(TAG, "Skipping upload — session UUID not yet available")
            return
        }

        withContext(Dispatchers.IO) {
            // Send camera frame if available.
            frame?.let { jpeg ->
                val ok = NoahApiClient.sendImage(uuid, jpeg)
                if (!ok) Log.w(TAG, "Image upload failed for uuid=$uuid")
            }

            // Send accumulated audio if available.
            if (chunks.isNotEmpty()) {
                val ok = NoahApiClient.sendAudio(uuid, chunks)
                if (!ok) Log.w(TAG, "Audio upload failed for uuid=$uuid")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Voice Activity Detection
    // -------------------------------------------------------------------------

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
