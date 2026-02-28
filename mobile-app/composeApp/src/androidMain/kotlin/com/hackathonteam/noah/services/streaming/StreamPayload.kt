package com.hackathonteam.noah.services.streaming

import com.hackathonteam.noah.tracking.TrackingState

/**
 * A single snapshot of all sensor streams, collected at the moment a
 * dispatch is triggered (periodic 5-second tick or voice activity).
 *
 * @param timestampMs      Wall-clock time at which this snapshot was taken.
 * @param trackingState    Latest activity classification from TrackingManager.
 * @param latestFrame      Most recent JPEG-compressed camera frame, or `null` if
 *                         the camera is inactive.
 * @param audioChunks      All PCM-16 mono audio chunks (~100 ms each) accumulated
 *                         since the last dispatch. Empty if microphone is inactive.
 * @param triggeredByVoice `true` when the dispatch was triggered by voice activity
 *                         rather than the periodic timer.
 */
data class StreamPayload(
    val timestampMs: Long,
    val trackingState: TrackingState,
    val latestFrame: ByteArray?,
    val audioChunks: List<ByteArray>,
    val triggeredByVoice: Boolean,
) {
    /** Convenience: total bytes of audio accumulated in this payload. */
    val totalAudioBytes: Int get() = audioChunks.sumOf { it.size }

    // ByteArray is compared by reference by default in data classes;
    // override so two payloads with identical frame content are considered equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamPayload) return false
        return timestampMs == other.timestampMs &&
            trackingState == other.trackingState &&
            latestFrame.contentEquals(other.latestFrame) &&
            audioChunks.size == other.audioChunks.size &&
            triggeredByVoice == other.triggeredByVoice
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + trackingState.hashCode()
        result = 31 * result + (latestFrame?.contentHashCode() ?: 0)
        result = 31 * result + audioChunks.size
        result = 31 * result + triggeredByVoice.hashCode()
        return result
    }

    override fun toString(): String =
        "StreamPayload(ts=$timestampMs, state=$trackingState, " +
        "frame=${latestFrame?.size ?: 0}B, " +
        "audio=${audioChunks.size} chunks / ${totalAudioBytes}B, " +
        "voice=$triggeredByVoice)"
}

// Extension to safely compare nullable ByteArray
private fun ByteArray?.contentEquals(other: ByteArray?): Boolean =
    when {
        this === other  -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
