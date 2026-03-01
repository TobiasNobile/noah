import argparse
import asyncio
import os
import sys
import wave
from base64 import b64encode
from pathlib import Path
from typing import Any, AsyncIterator

from dotenv import find_dotenv, load_dotenv
from elevenlabs.client import ElevenLabs
from mistralai import Mistral
from mistralai.extra.realtime import UnknownRealtimeEvent
from mistralai.models import (
    AudioFormat,
    RealtimeTranscriptionError,
    RealtimeTranscriptionSessionCreated,
    TranscriptionStreamDone,
    TranscriptionStreamTextDelta,
)

DEFAULT_REALTIME_MODEL = "voxtral-mini-transcribe-realtime-2602"
DEFAULT_BATCH_MODEL = "voxtral-mini-latest"
DEFAULT_TTS_VOICE_ID = "JBFqnCBsd6RMkjVDRZzb"
DEFAULT_TTS_MODEL_ID = "eleven_multilingual_v2"
DEFAULT_TTS_OUTPUT_FORMAT = "pcm_24000"


def _sample_rate_from_output_format(output_format: str) -> int:
    """Extract sample rate from ElevenLabs output format like `pcm_24000`."""
    try:
        return int(output_format.split("_")[-1])
    except (ValueError, IndexError) as exc:
        raise STTPipelineError(f"Unsupported output_format for WAV conversion: {output_format}") from exc


class STTPipelineError(RuntimeError):
    """Raised when the STT pipeline fails with an actionable message."""


def _require_pyaudio() -> Any:
    try:
        import pyaudio  # type: ignore
        return pyaudio
    except ModuleNotFoundError as exc:
        raise STTPipelineError("PyAudio is required for realtime microphone mode and local speaker playback.") from exc


def build_client() -> Mistral:
    dotenv_path = find_dotenv(usecwd=True)
    load_dotenv(dotenv_path if dotenv_path else None)

    api_key = (os.getenv("MISTRAL_API_KEY") or "").strip().strip('"').strip("'")
    if not api_key:
        raise STTPipelineError("MISTRAL_API_KEY is missing. Add it to your shell env or .env file.")

    return Mistral(api_key=api_key)


def build_elevenlabs_client() -> ElevenLabs:
    dotenv_path = find_dotenv(usecwd=True)
    load_dotenv(dotenv_path if dotenv_path else None)

    api_key = (os.getenv("ELEVENLABS_API_KEY") or "").strip().strip('"').strip("'")
    if not api_key:
        raise STTPipelineError("ELEVENLABS_API_KEY is missing. Add it to your shell env or .env file.")

    return ElevenLabs(api_key=api_key)


def transcribe_file(client: Mistral, audio_path: Path, model: str, language: str | None) -> str:
    try:
        with audio_path.open("rb") as audio_file:
            response = client.audio.transcriptions.complete(
                model=model,
                file={"file_name": audio_path.name, "content": audio_file},
                language=language,
                timestamp_granularities=["segment"],
            )
        return response.text
    except Exception as exc:
        raise STTPipelineError(f"Batch STT failed: {exc}") from exc


def transcribe_audio_file(audio_path: Path, model: str | None = None, language: str | None = None) -> str:
    """Transcribe a finalized audio file using Mistral batch STT."""
    normalized_path = audio_path.expanduser().resolve(strict=False)
    if not normalized_path.exists():
        raise FileNotFoundError(
            f"Audio file not found: {normalized_path} (cwd: {Path.cwd()})"
        )

    client = build_client()
    resolved_model = model or DEFAULT_BATCH_MODEL
    return transcribe_file(client, normalized_path, resolved_model, language)


async def iter_microphone(*, sample_rate: int, chunk_duration_ms: int) -> AsyncIterator[bytes]:
    """Yield microphone PCM16 mono chunks using PyAudio."""
    pyaudio = _require_pyaudio()
    p = pyaudio.PyAudio()
    chunk_samples = int(sample_rate * chunk_duration_ms / 1000)

    stream = p.open(
        format=pyaudio.paInt16,
        channels=1,
        rate=sample_rate,
        input=True,
        frames_per_buffer=chunk_samples,
    )

    loop = asyncio.get_running_loop()
    try:
        while True:
            data = await loop.run_in_executor(None, stream.read, chunk_samples, False)
            yield data
    finally:
        stream.stop_stream()
        stream.close()
        p.terminate()


async def transcribe_realtime_microphone(
    client: Mistral,
    model: str,
    sample_rate: int,
    chunk_duration_ms: int,
) -> None:
    audio_format = AudioFormat(encoding="pcm_s16le", sample_rate=sample_rate)
    audio_stream = iter_microphone(sample_rate=sample_rate, chunk_duration_ms=chunk_duration_ms)

    print("Listening... Press Ctrl+C to stop.")

    async for event in client.audio.realtime.transcribe_stream(
        audio_stream=audio_stream,
        model=model,
        audio_format=audio_format,
    ):
        if isinstance(event, RealtimeTranscriptionSessionCreated):
            print("Session created.")
        elif isinstance(event, TranscriptionStreamTextDelta):
            print(event.text, end="", flush=True)
        elif isinstance(event, TranscriptionStreamDone):
            print("\nTranscription done.")
        elif isinstance(event, RealtimeTranscriptionError):
            raise STTPipelineError(f"Realtime transcription error: {event}")
        elif isinstance(event, UnknownRealtimeEvent):
            print(f"\nUnknown event: {event}")


async def text_to_speech(text: str) -> None:
    """Use Eleven Labs to convert text to speech and play it."""
    audio_data = await synthesize_speech_bytes(text)
    loop = asyncio.get_running_loop()

    def sync_play(data):
        pyaudio = _require_pyaudio()
        p = pyaudio.PyAudio()
        stream = p.open(
            format=pyaudio.paInt16,
            channels=1,
            rate=24000,
            output=True,
        )
        stream.write(data)
        stream.stop_stream()
        stream.close()
        p.terminate()

    await loop.run_in_executor(None, sync_play, audio_data)


async def synthesize_speech_bytes(
    text: str,
    voice_id: str = DEFAULT_TTS_VOICE_ID,
    model_id: str = DEFAULT_TTS_MODEL_ID,
    output_format: str = DEFAULT_TTS_OUTPUT_FORMAT,
) -> bytes:
    """Convert text to speech bytes using ElevenLabs without local playback."""
    client = build_elevenlabs_client()
    loop = asyncio.get_running_loop()

    def sync_convert() -> bytes:
        audio_stream = client.text_to_speech.convert(
            text=text,
            voice_id=voice_id,
            model_id=model_id,
            output_format=output_format,
        )
        return b"".join(audio_stream)

    try:
        return await loop.run_in_executor(None, sync_convert)
    except Exception as exc:
        raise STTPipelineError(f"TTS generation failed: {exc}") from exc


async def synthesize_speech_base64(
    text: str,
    voice_id: str = DEFAULT_TTS_VOICE_ID,
    model_id: str = DEFAULT_TTS_MODEL_ID,
    output_format: str = DEFAULT_TTS_OUTPUT_FORMAT,
) -> str:
    """Convert text to speech and return base64-encoded bytes for API transport."""
    audio_data = await synthesize_speech_bytes(
        text=text,
        voice_id=voice_id,
        model_id=model_id,
        output_format=output_format,
    )
    return b64encode(audio_data).decode("utf-8")


async def synthesize_speech_wav_file(
    text: str,
    output_path: Path,
    voice_id: str = DEFAULT_TTS_VOICE_ID,
    model_id: str = DEFAULT_TTS_MODEL_ID,
    output_format: str = DEFAULT_TTS_OUTPUT_FORMAT,
) -> Path:
    """Generate speech from text and save it as a WAV file."""
    if not output_format.startswith("pcm_"):
        raise STTPipelineError("WAV export requires a PCM output_format such as pcm_24000.")

    sample_rate = _sample_rate_from_output_format(output_format)
    audio_data = await synthesize_speech_bytes(
        text=text,
        voice_id=voice_id,
        model_id=model_id,
        output_format=output_format,
    )

    normalized_output = output_path.expanduser().resolve(strict=False)
    normalized_output.parent.mkdir(parents=True, exist_ok=True)

    with wave.open(str(normalized_output), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(audio_data)

    return normalized_output


async def text_to_speech_input() -> None:
    """Read text from stdin and play it as speech."""
    print("Enter text to speak. Press Ctrl+C to stop.")
    loop = asyncio.get_running_loop()
    while True:
        text = await loop.run_in_executor(None, sys.stdin.readline)
        if not text:
            break
        await text_to_speech(text.strip())


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Simple STT with Mistral (realtime mic + batch file)")
    parser.add_argument("audio", nargs="?", type=Path, help="Audio file path (required for --mode batch)")
    parser.add_argument("--mode", choices=["realtime", "batch", "tts"], default="realtime")
    parser.add_argument("--model", default=None, help="Transcription model override")
    parser.add_argument("--language", default=None, help="Batch mode optional language hint")
    parser.add_argument("--sample-rate", type=int, default=16000, help="Realtime microphone sample rate")
    parser.add_argument("--chunk-duration-ms", type=int, default=480, help="Realtime microphone chunk duration")
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    dotenv_path = find_dotenv(usecwd=True)
    load_dotenv(dotenv_path if dotenv_path else None)

    if args.mode == "batch":
        if args.audio is None:
            raise STTPipelineError("Batch mode requires an audio file path, e.g. python main.py ./audio.wav --mode batch")
        transcript = transcribe_audio_file(args.audio, args.model, args.language)
        print(transcript)
        return
    elif args.mode == "tts":
        try:
            asyncio.run(text_to_speech_input())
        except KeyboardInterrupt:
            print("\nStopping...")
        return

    client = build_client()
    model = args.model or DEFAULT_REALTIME_MODEL

    try:
        asyncio.run(
            transcribe_realtime_microphone(
                client,
                model,
                args.sample_rate,
                args.chunk_duration_ms,
            )
        )
    except KeyboardInterrupt:
        print("\nStopping...")


if __name__ == "__main__":
    sys.exit(main())
