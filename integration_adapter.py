"""Framework-agnostic helpers to integrate STT/TTS with Flask/FastAPI apps."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

from stt_pipeline import (
    STTPipelineError,
    synthesize_speech_base64,
    synthesize_speech_wav_file,
    transcribe_audio_file,
)


def stt_from_file(path: str, model: str | None = None, language: str | None = None) -> dict[str, Any]:
    """Transcribe a finalized audio file and return a JSON-safe payload."""
    try:
        transcript = transcribe_audio_file(Path(path), model=model, language=language)
        return {"ok": True, "text": transcript}
    except FileNotFoundError as exc:
        return {
            "ok": False,
            "error_code": "audio_not_found",
            "error_message": str(exc),
        }
    except STTPipelineError as exc:
        return {
            "ok": False,
            "error_code": "stt_error",
            "error_message": str(exc),
        }


def tts_to_base64(
    text: str,
    voice_id: str | None = None,
    model_id: str | None = None,
    output_format: str | None = None,
) -> dict[str, Any]:
    """Generate TTS audio and return it base64-encoded for API responses."""
    kwargs: dict[str, str] = {}
    if voice_id:
        kwargs["voice_id"] = voice_id
    if model_id:
        kwargs["model_id"] = model_id
    if output_format:
        kwargs["output_format"] = output_format

    try:
        audio_base64 = asyncio.run(synthesize_speech_base64(text, **kwargs))
        mime = "audio/pcm" if (output_format or "").startswith("pcm") else "audio/mpeg"
        return {
            "ok": True,
            "audio_base64": audio_base64,
            "mime": mime,
        }
    except STTPipelineError as exc:
        return {
            "ok": False,
            "error_code": "tts_error",
            "error_message": str(exc),
        }


def tts_to_wav_file(
    text: str,
    output_path: str,
    voice_id: str | None = None,
    model_id: str | None = None,
    output_format: str | None = None,
) -> dict[str, Any]:
    """Generate TTS audio and save it as a WAV file."""
    kwargs: dict[str, str] = {}
    if voice_id:
        kwargs["voice_id"] = voice_id
    if model_id:
        kwargs["model_id"] = model_id
    if output_format:
        kwargs["output_format"] = output_format

    try:
        saved_path = asyncio.run(synthesize_speech_wav_file(text, Path(output_path), **kwargs))
        return {
            "ok": True,
            "wav_path": str(saved_path),
        }
    except STTPipelineError as exc:
        return {
            "ok": False,
            "error_code": "tts_error",
            "error_message": str(exc),
        }
