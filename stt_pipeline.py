import argparse
import asyncio
import os
import sys
from pathlib import Path
from typing import AsyncIterator

from dotenv import find_dotenv, load_dotenv
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


class STTPipelineError(RuntimeError):
    """Raised when the STT pipeline fails with an actionable message."""


def build_client() -> Mistral:
    dotenv_path = find_dotenv(usecwd=True)
    load_dotenv(dotenv_path if dotenv_path else None)

    api_key = (os.getenv("MISTRAL_API_KEY") or "").strip().strip('"').strip("'")
    if not api_key:
        raise STTPipelineError("MISTRAL_API_KEY is missing. Add it to your shell env or .env file.")

    return Mistral(api_key=api_key)


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


async def iter_microphone(*, sample_rate: int, chunk_duration_ms: int) -> AsyncIterator[bytes]:
    """Yield microphone PCM16 mono chunks using PyAudio."""
    try:
        import pyaudio
    except ImportError as exc:
        raise STTPipelineError(
            "PyAudio is required for realtime microphone streaming. Install it first."
        ) from exc

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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Simple STT with Mistral (realtime mic + batch file)")
    parser.add_argument("audio", nargs="?", type=Path, help="Audio file path (required for --mode batch)")
    parser.add_argument("--mode", choices=["realtime", "batch"], default="realtime")
    parser.add_argument("--model", default=None, help="Transcription model override")
    parser.add_argument("--language", default=None, help="Batch mode optional language hint")
    parser.add_argument("--sample-rate", type=int, default=16000, help="Realtime microphone sample rate")
    parser.add_argument("--chunk-duration-ms", type=int, default=480, help="Realtime microphone chunk duration")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    client = build_client()

    if args.mode == "batch":
        if args.audio is None:
            raise STTPipelineError("Batch mode requires an audio file path, e.g. python main.py ./audio.wav --mode batch")
        if not args.audio.exists():
            raise FileNotFoundError(f"Audio file not found: {args.audio}")

        model = args.model or DEFAULT_BATCH_MODEL
        transcript = transcribe_file(client, args.audio, model, args.language)
        print(transcript)
        return

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
