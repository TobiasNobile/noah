import argparse
import asyncio
import os
import sys
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


class STTPipelineError(RuntimeError):
    """Raised when the STT pipeline fails with an actionable message."""


def build_client() -> Mistral:
    dotenv_path = find_dotenv(usecwd=True)
    load_dotenv(dotenv_path if dotenv_path else None)

    api_key = (os.getenv("MISTRAL_API_KEY") or "").strip().strip('"').strip("'")
    if not api_key:
        raise STTPipelineError("MISTRAL_API_KEY is missing. Add it to your shell env or .env file.")

    return Mistral(api_key=api_key)


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
    parser = argparse.ArgumentParser(description="Simple realtime STT with Mistral + microphone")
    parser.add_argument("--model", default=DEFAULT_REALTIME_MODEL, help="Realtime transcription model")
    parser.add_argument("--sample-rate", type=int, default=16000, help="Microphone sample rate")
    parser.add_argument("--chunk-duration-ms", type=int, default=480, help="Microphone chunk duration")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    client = build_client()

    try:
        asyncio.run(
            transcribe_realtime_microphone(
                client,
                args.model,
                args.sample_rate,
                args.chunk_duration_ms,
            )
        )
    except KeyboardInterrupt:
        print("\nStopping...")


if __name__ == "__main__":
    sys.exit(main())
