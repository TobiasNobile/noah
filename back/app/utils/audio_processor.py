"""
Audio processing utilities for handling continuous audio chunks.
Converts raw PCM audio to WAV format for AI processing.
"""
import io
import wave
import logging
from typing import List

import numpy as np

logger = logging.getLogger(__name__)

class AudioProcessor:
    """Handles accumulation and processing of audio chunks."""
    
    # Audio configuration (match the Android MicrophoneSensor to recreate the same audio format)
    SAMPLE_RATE_HZ = 16_000
    CHANNELS = 1  # MONO
    SAMPLE_WIDTH = 2  # PCM16 = 2 bytes per sample
    
    def __init__(self):
        self.audio_buffer: List[bytes] = []
        self.total_bytes = 0
    
    def add_chunk(self, chunk: bytes) -> None:
        """Add an audio chunk to the buffer."""
        self.audio_buffer.append(chunk)
        self.total_bytes += len(chunk)
        logger.debug(f"Added audio chunk: {len(chunk)} bytes (total: {self.total_bytes} bytes)")
    
    def get_duration_seconds(self) -> float:
        """Calculate audio duration in seconds."""
        if self.total_bytes == 0:
            return 0.0
        num_samples = self.total_bytes // self.SAMPLE_WIDTH
        duration = num_samples / self.SAMPLE_RATE_HZ
        return duration
    
    def to_wav_bytes(self) -> bytes:
        """Convert accumulated PCM chunks to WAV format, stripping silence."""
        if not self.audio_buffer:
            logger.warning("No audio chunks to convert")
            return b""
        
        # Concatenate all chunks into one PCM stream
        pcm_data = b"".join(self.audio_buffer)

        original_len = len(pcm_data)
        pcm_data = self._strip_silence(pcm_data)
        stripped_len = len(pcm_data)
        saved_pct = (1 - stripped_len / original_len) * 100 if original_len else 0
        logger.info(
            f"Silence removal: {original_len} -> {stripped_len} bytes "
            f"({saved_pct:.1f}% stripped)"
        )

        if not pcm_data:
            logger.warning("Audio is entirely silent after stripping")
            return b""

        # Create WAV file in memory
        wav_buffer = io.BytesIO()
        with wave.open(wav_buffer, "wb") as wav_file:
            wav_file.setnchannels(self.CHANNELS)
            wav_file.setsampwidth(self.SAMPLE_WIDTH)
            wav_file.setframerate(self.SAMPLE_RATE_HZ)
            wav_file.writeframes(pcm_data)
        
        wav_bytes = wav_buffer.getvalue()
        num_samples = stripped_len // self.SAMPLE_WIDTH
        duration = num_samples / self.SAMPLE_RATE_HZ
        logger.debug(f"Generated WAV file: {len(wav_bytes)} bytes, duration: {duration:.2f}s")
        
        return wav_bytes

    # ── silence stripping ────────────────────────────────────────────────

    # Duration (in seconds) of each analysis frame
    _FRAME_DURATION_S = 0.02          # 20 ms
    # RMS threshold below which a frame is considered silent (PCM-16 range)
    _SILENCE_RMS_THRESHOLD = 200
    # Number of silent frames to keep around speech for natural transitions
    _PAD_FRAMES = 5

    def _strip_silence(self, pcm_data: bytes) -> bytes:
        """Remove contiguous silent regions from raw PCM-16 mono data.

        Keeps a small padding of *_PAD_FRAMES* silent frames on each side of
        every voiced segment so the audio still sounds natural.
        """
        samples = np.frombuffer(pcm_data, dtype=np.int16)
        frame_size = int(self.SAMPLE_RATE_HZ * self._FRAME_DURATION_S)  # samples per frame

        if len(samples) < frame_size:
            return pcm_data  # too short to analyse

        n_frames = len(samples) // frame_size
        # Trim to an exact multiple of frame_size
        samples = samples[: n_frames * frame_size]
        frames = samples.reshape(n_frames, frame_size)

        # Compute RMS energy per frame
        rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))

        # Boolean mask: True = voiced frame
        voiced = rms >= self._SILENCE_RMS_THRESHOLD

        # Expand the mask by _PAD_FRAMES in each direction so we keep a
        # natural transition around speech.
        padded = voiced.copy()
        for offset in range(1, self._PAD_FRAMES + 1):
            padded[offset:] |= voiced[:-offset]       # look-back
            padded[:-offset] |= voiced[offset:]        # look-ahead

        kept_frames = frames[padded]
        return kept_frames.tobytes()

    def to_pcm_bytes(self) -> bytes:
        """Get raw PCM data (no WAV header)."""
        pcm_data = b"".join(self.audio_buffer)
        logger.debug(f"Generated PCM data: {len(pcm_data)} bytes")
        return pcm_data
    
    def clear(self) -> None:
        """Clear the audio buffer."""
        self.audio_buffer.clear()
        self.total_bytes = 0
        logger.debug("Audio buffer cleared")
    
    def get_stats(self) -> dict:
        """Get statistics about the accumulated audio."""
        return {
            "total_bytes": self.total_bytes,
            "chunks_count": len(self.audio_buffer),
            "duration_seconds": self.get_duration_seconds(),
            "sample_rate": self.SAMPLE_RATE_HZ,
            "channels": self.CHANNELS,
        }

