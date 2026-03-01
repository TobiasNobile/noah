package com.hackathonteam.noah.services.streaming

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

private const val TAG = "StreamDispatcher"
private const val PERIODIC_INTERVAL_MS = 5_000L
private const val USER_INFO_INTERVAL_MS = 10_000L
private const val VOICE_ACTIVITY_RMS_THRESHOLD = 800.0
private const val SILENCE_TIMEOUT_MS = 2_000L
private const val ANSWER_WAIT_TIMEOUT_MS = 120_000L   // 2 minutes — stop tracking if exceeded

object StreamDispatcher {

    // -------------------------------------------------------------------------
    // Public observable stream (kept for in-app observers / debugging)
    // -------------------------------------------------------------------------

    private val _payloads = MutableSharedFlow<StreamPayload>(extraBufferCapacity = 16)

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

    @Volatile private var hasVoiceActivity: Boolean = false

    private val _sessionUuid = MutableStateFlow<String?>(null)
    private var sessionUuid: String?
        get() = _sessionUuid.value
        set(value) { _sessionUuid.value = value }

    private suspend fun awaitUuid(): String = _sessionUuid.filterNotNull().first()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun start() {
        if (audioCollectorJob?.isActive == true) return
        Log.d(TAG, "StreamDispatcher started")

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

        userInfoJob = scope.launch(Dispatchers.IO) {
            val uuid = awaitUuid()
            while (true) {
                delay(USER_INFO_INTERVAL_MS)
                val ok = NoahApiClient.sendUserInfo(uuid, UserInfo)
                if (!ok) Log.w(TAG, "Periodic sendUserInfo failed for uuid=$uuid")
                else Log.d(TAG, "Periodic sendUserInfo sent for uuid=$uuid")
            }
        }

        audioCollectorJob = scope.launch {
            MicrophoneSensor.audioChunks.collect { pcm ->
                val uuid = sessionUuid
                if (uuid != null) {
                    scope.launch(Dispatchers.IO) {
                        val ok = NoahApiClient.sendAudioChunk(uuid, pcm)
                        if (!ok) Log.w(TAG, "Audio chunk upload failed for uuid=$uuid")
                    }
                } else {
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
                    hasVoiceActivity = true
                    silenceJob?.cancel()
                    silenceJob = scope.launch {
                        delay(SILENCE_TIMEOUT_MS)
                        onSilenceTimeout()
                    }
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
                // finishAudio retourne Boolean
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
            // finishAudio retourne Boolean — on attend le résultat avec timeout
            val ok = withTimeoutOrNull(ANSWER_WAIT_TIMEOUT_MS) {
                NoahApiClient.finishAudio(uuid)
            }

            when {
                ok == null -> {
                    // Timed out
                    Log.w(TAG, "LLM answer timed out after ${ANSWER_WAIT_TIMEOUT_MS / 1000}s — stopping tracking")
                    withContext(Dispatchers.Main) {
                        TrackingManager.stopListening()
                    }
                }
                ok == true -> {
                    Log.d(TAG, "/audio/finish succeeded for uuid=$uuid")
                    dispatch(triggeredByVoice = true)
                }
                else -> {
                    Log.w(TAG, "/audio/finish failed for uuid=$uuid")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Audio playback
    // -------------------------------------------------------------------------

    private fun playResponseAudio(wavBytes: ByteArray) {
        try {
            if (wavBytes.size < 44) {
                Log.w(TAG, "Response audio too small to be a valid WAV (${wavBytes.size} bytes)")
                return
            }

            val channels      = ((wavBytes[23].toInt() and 0xFF) shl 8) or (wavBytes[22].toInt() and 0xFF)
            val sampleRate    = ((wavBytes[27].toInt() and 0xFF) shl 24) or
                    ((wavBytes[26].toInt() and 0xFF) shl 16) or
                    ((wavBytes[25].toInt() and 0xFF) shl  8) or
                    (wavBytes[24].toInt() and 0xFF)
            val bitsPerSample = ((wavBytes[35].toInt() and 0xFF) shl 8) or (wavBytes[34].toInt() and 0xFF)

            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val encoding    = if (bitsPerSample == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

            val pcmOffset = 44
            val pcmLength = wavBytes.size - pcmOffset

            if (pcmLength <= 0) {
                Log.w(TAG, "WAV file has no PCM data after header")
                return
            }

            Log.d(TAG, "Playing response audio: sampleRate=$sampleRate  channels=$channels  bits=$bitsPerSample  pcmBytes=$pcmLength")

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(pcmLength)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(wavBytes, pcmOffset, pcmLength)
            audioTrack.play()

            val totalFrames = pcmLength / (bitsPerSample / 8) / channels
            while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING &&
                audioTrack.playbackHeadPosition < totalFrames) {
                Thread.sleep(50)
            }

            audioTrack.stop()
            audioTrack.release()
            Log.d(TAG, "Response audio playback complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing response audio: ${e.message}", e)
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

        val uuid = sessionUuid
        if (uuid == null) {
            Log.w(TAG, "Skipping upload — session UUID not yet available")
            return
        }

        withContext(Dispatchers.IO) {
            frame?.let { jpeg ->
                val ok = NoahApiClient.sendImage(uuid, jpeg)
                if (!ok) Log.w(TAG, "Image upload failed for uuid=$uuid")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Voice Activity Detection
    // -------------------------------------------------------------------------

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