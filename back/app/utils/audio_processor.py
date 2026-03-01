"""
Audio processing utilities for handling continuous audio chunks.
Converts raw PCM audio to WAV format for AI processing.
"""
import io
import wave
import logging
from typing import List

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
        """Convert accumulated PCM chunks to WAV format."""
        if not self.audio_buffer:
            logger.warning("No audio chunks to convert")
            return b""
        
        # Concatenate all chunks into one PCM stream
        pcm_data = b"".join(self.audio_buffer)
        
        # Create WAV file in memory
        wav_buffer = io.BytesIO()
        with wave.open(wav_buffer, "wb") as wav_file:
            wav_file.setnchannels(self.CHANNELS)
            wav_file.setsampwidth(self.SAMPLE_WIDTH)
            wav_file.setframerate(self.SAMPLE_RATE_HZ)
            wav_file.writeframes(pcm_data)
        
        wav_bytes = wav_buffer.getvalue()
        duration = self.get_duration_seconds()
        logger.debug(f"Generated WAV file: {len(wav_bytes)} bytes, duration: {duration:.2f}s")
        
        return wav_bytes
    
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

