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
private const val ANSWER_WAIT_TIMEOUT_MS = 120_000L

object StreamDispatcher {

    private val _payloads = MutableSharedFlow<StreamPayload>(extraBufferCapacity = 16)
    val payloads: SharedFlow<StreamPayload> = _payloads.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val audioMutex = Mutex()
    private val pendingAudioChunks = mutableListOf<ByteArray>()

    private var audioCollectorJob: Job? = null
    private var gpsCollectorJob: Job? = null
    private var userInfoJob: Job? = null
    private var periodicJob: Job? = null
    private var silenceJob: Job? = null

    @Volatile private var hasVoiceActivity: Boolean = false

    // --- Interruption de la lecture audio ---
    @Volatile private var currentAudioTrack: AudioTrack? = null
    @Volatile private var isPlaybackInterrupted: Boolean = false
    /** True while the AI response audio is playing — chunks and VAD are suppressed. */
    @Volatile private var isAiSpeaking: Boolean = false

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
                // Discard microphone data while the AI is speaking — the mic
                // picks up the speaker output and we don't want to send it.
                if (isAiSpeaking) return@collect

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
                    // User spoke → interrupt the AI immediately
                    interruptResponseAudio()

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

    fun stop(resetSession: Boolean = true) {
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

        interruptResponseAudio()

        if (resetSession) sessionUuid = null     // clear immediately, before the flush coroutine runs

        val uuid = sessionUuid                    // null if reset, old value otherwise
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
                val result = NoahApiClient.finishAudio(uuid)
                if (result is FinishAudioResult.Error) Log.w(TAG, "POST /audio/finish failed on stop — ${result.reason}")
            }
        } else {
            scope.launch { audioMutex.withLock { pendingAudioChunks.clear() } }
        }

        Log.d(TAG, "StreamDispatcher stopped (resetSession=$resetSession)")
    }

    // -------------------------------------------------------------------------
    // Interruption audio
    // -------------------------------------------------------------------------

    /**
     * Stoppe immédiatement la lecture en cours si le LLM est en train de parler.
     * Appelé dès que la voix de l'utilisateur est détectée.
     */
    private fun interruptResponseAudio() {
        isPlaybackInterrupted = true          // signal the playback loop to exit immediately
        isAiSpeaking = false                  // re-enable mic chunk sending immediately
        currentAudioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                    Log.d(TAG, "Response audio interrupted by user voice activity")
                }
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error interrupting audio track: ${e.message}")
            } finally {
                currentAudioTrack = null
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch logic
    // -------------------------------------------------------------------------

    private suspend fun onSilenceTimeout() {
        val uuid = sessionUuid
        Log.d(TAG, "Silence timeout — sending image then /audio/finish for uuid=$uuid")
        hasVoiceActivity = false
        silenceJob = null

        if (uuid == null) {
            Log.w(TAG, "/audio/finish skipped — no session UUID")
            return
        }

        withContext(Dispatchers.IO) {
            // 1. Send the latest camera frame BEFORE finishing the audio turn.
            val frame = latestCameraFrame.value
            if (frame != null) {
                val ok = NoahApiClient.sendImage(uuid, frame)
                if (ok) Log.d(TAG, "Image sent before /audio/finish — uuid=$uuid  bytes=${frame.size}")
                else    Log.w(TAG, "Image upload failed before /audio/finish — uuid=$uuid")
            } else {
                Log.d(TAG, "No camera frame available to send before /audio/finish")
            }

            // 2. Signal the server to assemble the WAV and run the LLM.
            //    Wait up to 2 minutes for the answer + response audio.
            val result = withTimeoutOrNull(ANSWER_WAIT_TIMEOUT_MS) {
                NoahApiClient.finishAudio(uuid)
            }

            when {
                result == null -> {
                    Log.w(TAG, "LLM answer timed out after ${ANSWER_WAIT_TIMEOUT_MS / 1000}s — stopping tracking")
                    withContext(Dispatchers.Main) { TrackingManager.stopListening() }
                }
                result is FinishAudioResult.Success -> {
                    Log.d(TAG, "/audio/finish succeeded — answer: ${result.answer.take(120)}")
                    if (result.audioBytes.isNotEmpty()) {
                        playResponseAudio(result.audioBytes)
                    } else {
                        Log.d(TAG, "No response audio bytes received")
                    }
                    dispatch(triggeredByVoice = true)
                }
                result is FinishAudioResult.Error -> {
                    Log.w(TAG, "/audio/finish failed for uuid=$uuid — ${result.reason}")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Audio playback
    // -------------------------------------------------------------------------

    private suspend fun playResponseAudio(wavBytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            if (wavBytes.size < 44) {
                Log.w(TAG, "Response audio too small to be a valid WAV (${wavBytes.size} bytes)")
                return@withContext
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
                return@withContext
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

            // Reset the interrupt flag, mark AI as speaking, and store the track reference
            // BEFORE playing so interruptResponseAudio() can act on it immediately.
            isPlaybackInterrupted = false
            isAiSpeaking = true
            currentAudioTrack = audioTrack
            audioTrack.play()

            val totalFrames = pcmLength / (bitsPerSample / 8) / channels
            // Poll until playback finishes OR the interrupt flag is set by voice activity.
            while (!isPlaybackInterrupted &&
                   audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING &&
                   audioTrack.playbackHeadPosition < totalFrames) {
                Thread.sleep(30)
            }

            // Release only if not already released by interruptResponseAudio().
            if (currentAudioTrack === audioTrack) {
                audioTrack.stop()
                audioTrack.release()
                currentAudioTrack = null
            }
            isAiSpeaking = false

            if (isPlaybackInterrupted) {
                Log.d(TAG, "Response audio playback interrupted (user spoke)")
            } else {
                Log.d(TAG, "Response audio playback complete")
            }
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