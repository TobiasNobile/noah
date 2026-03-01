package com.hackathonteam.noah.services.streaming

import android.util.Log
import com.hackathonteam.noah.services.sensor.audio.MicrophoneSensor
import com.hackathonteam.noah.services.sensor.location.GpsSensor
import com.hackathonteam.noah.tracking.TrackingManager
import com.hackathonteam.noah.ui.interactions.latestCameraFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val TAG = "StreamDispatcher"
private const val PERIODIC_INTERVAL_MS = 5_000L
private const val USER_INFO_INTERVAL_MS = 10_000L
private const val VOICE_ACTIVITY_RMS_THRESHOLD = 800.0
private const val SILENCE_TIMEOUT_MS = 4_000L

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
    private var gpsCollectorJob: Job? = null
    private var userInfoJob: Job? = null
    private var periodicJob: Job? = null
    private var silenceJob: Job? = null

    /** True once at least one voice-active chunk has been received since the last /audio/finish. */
    @Volatile private var hasVoiceActivity: Boolean = false

    /**
     * Session UUID returned by POST /register.
     * Exposed as a StateFlow so coroutines can suspend-wait for it to become
     * available without polling.
     */
    private val _sessionUuid = MutableStateFlow<String?>(null)
    private var sessionUuid: String?
        get() = _sessionUuid.value
        set(value) { _sessionUuid.value = value }

    /** Suspends until the session UUID is available, then returns it. */
    private suspend fun awaitUuid(): String = _sessionUuid.filterNotNull().first()

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
                Log.d(TAG, "Registering session via POST /register …")
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

        // send UserInfo every 10 seconds unconditionally, so the server
        // always has fresh GPS + activity state even if GPS events slow down.
        userInfoJob = scope.launch(Dispatchers.IO) {
            val uuid = awaitUuid()
            while (true) {
                delay(USER_INFO_INTERVAL_MS)
                val ok = NoahApiClient.sendUserInfo(uuid, UserInfo)
                if (!ok) Log.w(TAG, "Periodic sendUserInfo failed for uuid=$uuid")
                else Log.d(TAG, "Periodic sendUserInfo sent for uuid=$uuid")
            }
        }

        // Collect audio chunks and forward each one immediately to /audio/chunk.
        audioCollectorJob = scope.launch {
            MicrophoneSensor.audioChunks.collect { pcm ->
                // Upload each chunk to the server immediately (no buffering).
                val uuid = sessionUuid
                if (uuid != null) {
                    scope.launch(Dispatchers.IO) {
                        val ok = NoahApiClient.sendAudioChunk(uuid, pcm)
                        if (!ok) Log.w(TAG, "Audio chunk upload failed for uuid=$uuid")
                    }
                } else {
                    // Session not yet registered — buffer locally as fallback.
                    audioMutex.withLock { pendingAudioChunks.add(pcm) }
                    scope.launch(Dispatchers.IO) {
                        val readyUuid = awaitUuid()
                        val pending = audioMutex.withLock {
                            val snapshot = pendingAudioChunks.toList()
                            pendingAudioChunks.clear()
                            snapshot
                        }
                        for (chunk in pending) {
                            NoahApiClient.sendAudioChunk(readyUuid, chunk)
                        }
                    }
                }

                if (isVoiceActivity(pcm)) {
                    // Voice detected: mark activity and reset the silence countdown.
                    hasVoiceActivity = true
                    silenceJob?.cancel()
                    silenceJob = scope.launch {
                        delay(SILENCE_TIMEOUT_MS)
                        onSilenceTimeout()
                    }
                }
                // Silent chunks while no voice has started yet are intentionally ignored —
                // we only start the countdown once the user has actually spoken.
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
        gpsCollectorJob?.cancel()
        userInfoJob?.cancel()
        periodicJob?.cancel()
        silenceJob?.cancel()
        audioCollectorJob = null
        gpsCollectorJob = null
        userInfoJob = null
        periodicJob = null
        silenceJob = null
        hasVoiceActivity = false

        // Flush any buffered chunks (accumulated while UUID was not yet available)
        // and then signal the server that recording is complete.
        val uuid = sessionUuid
        if (uuid != null) {
            scope.launch(Dispatchers.IO) {
                val remaining = audioMutex.withLock {
                    val snapshot = pendingAudioChunks.toList()
                    pendingAudioChunks.clear()
                    snapshot
                }
                for (chunk in remaining) {
                    val ok = NoahApiClient.sendAudioChunk(uuid, chunk)
                    if (!ok) Log.w(TAG, "Flush: audio chunk upload failed for uuid=$uuid")
                }
                val ok = NoahApiClient.finishAudio(uuid)
                if (!ok) Log.w(TAG, "POST /audio/finish failed for uuid=$uuid")
            }
        } else {
            scope.launch { audioMutex.withLock { pendingAudioChunks.clear() } }
        }

        if (resetSession) sessionUuid = null
        Log.d(TAG, "StreamDispatcher stopped (resetSession=$resetSession)")
    }

    // -------------------------------------------------------------------------
    // Dispatch logic
    // -------------------------------------------------------------------------

    /**
     * Called after [SILENCE_TIMEOUT_MS] of silence following voice activity.
     * Signals the server to assemble the WAV and process the turn, then resets
     * the voice-activity flag so the next utterance starts a fresh turn.
     * The microphone keeps running and chunks keep flowing — the server's
     * [processor.clear()] inside /audio/finish ensures the next /audio/finish
     * will only contain audio from the new turn.
     */
    private suspend fun onSilenceTimeout() {
        val uuid = sessionUuid
        Log.d(TAG, "Silence timeout — sending /audio/finish for uuid=$uuid")
        hasVoiceActivity = false
        silenceJob = null

        if (uuid == null) {
            Log.w(TAG, "/audio/finish skipped — no session UUID")
            return
        }

        withContext(Dispatchers.IO) {
            val ok = NoahApiClient.finishAudio(uuid)
            if (ok) {
                Log.d(TAG, "/audio/finish succeeded — server is processing the turn")
                dispatch(triggeredByVoice = true)
            } else {
                Log.w(TAG, "/audio/finish failed for uuid=$uuid")
            }
        }
    }

    private suspend fun dispatch(triggeredByVoice: Boolean) {
        val frame = latestCameraFrame.value

        val payload = StreamPayload(
            timestampMs      = System.currentTimeMillis(),
            trackingState    = TrackingManager.trackingState,
            latestFrame      = frame,
            audioChunks      = emptyList(),
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
            // Audio chunks are sent immediately on arrival via sendAudioChunk,
            // so no batch audio upload is needed here.
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
