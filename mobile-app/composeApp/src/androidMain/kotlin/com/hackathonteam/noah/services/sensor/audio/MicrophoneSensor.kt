package com.hackathonteam.noah.services.sensor.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.hackathonteam.noah.services.sensor.AudioSensorStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "MicrophoneSensor"

/**
 * Captures raw PCM audio from the device microphone and streams it as
 * [ByteArray] chunks via [audioChunks].
 *
 * Audio configuration
 * -------------------
 * - Source  : [MediaRecorder.AudioSource.MIC]
 * - Rate    : 16 000 Hz
 * - Channel : MONO
 *
 * Each chunk emitted on [audioChunks] is a raw PCM frame of [CHUNK_SIZE_BYTES]
 * bytes. Emission happens on a dedicated IO coroutine
 *
 * Usage
 * -----
 * Call [startListening] once RECORD_AUDIO permission has been granted.
 * Call [stopListening] to release the [AudioRecord] and stop the capture thread.
 * Both are handled automatically by TrackingManager.
 */
object MicrophoneSensor : AudioSensorStrategy {

    private const val SAMPLE_RATE_HZ   = 16_000
    private const val CHANNEL_CONFIG   = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT     = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Number of bytes read per capture loop iteration.
     * 16 000 Hz × 2 bytes (PCM16) × 0.1 s = 3 200 bytes → ~100 ms chunks.
     */
    private const val CHUNK_SIZE_BYTES = 3_200

    // ---- Runtime state ----
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _audioChunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val audioChunks: SharedFlow<ByteArray> = _audioChunks.asSharedFlow()

    override fun startListening(context: Context) {
        if (audioRecord != null) {
            Log.w(TAG, "startListening called but already recording — ignoring")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted — cannot start microphone")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
        )
        // Use at least 2× the minimum buffer to avoid overruns.
        val bufferSize = maxOf(minBuffer * 2, CHUNK_SIZE_BYTES * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }

        audioRecord = record
        record.startRecording()
        Log.d(TAG, "Microphone started — sample rate: ${SAMPLE_RATE_HZ} Hz, chunk: ${CHUNK_SIZE_BYTES} B")

        captureJob = scope.launch {
            val chunk = ByteArray(CHUNK_SIZE_BYTES)
            while (isActive) {
                val bytesRead = record.read(chunk, 0, chunk.size)
                when {
                    bytesRead > 0 -> {
                        val pcm = chunk.copyOf(bytesRead)
                        _audioChunks.emit(pcm)
                        Log.d(TAG, "Audio chunk emitted: ${pcm.size} bytes")
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION ->
                        Log.e(TAG, "read() — ERROR_INVALID_OPERATION")
                    bytesRead == AudioRecord.ERROR_BAD_VALUE ->
                        Log.e(TAG, "read() — ERROR_BAD_VALUE")
                }
            }
        }
    }

    override fun stopListening() {
        captureJob?.cancel()
        captureJob = null

        audioRecord?.let { record ->
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
            record.release()
        }
        audioRecord = null

        Log.d(TAG, "Microphone stopped")
    }
}


