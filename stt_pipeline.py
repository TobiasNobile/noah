import argparse
import asyncio
import os
import wave
from pathlib import Path
from typing import AsyncIterator

from dotenv import load_dotenv
from mistralai import Mistral
from mistralai.models import AudioFormat

DEFAULT_STT_MODEL = "voxtral-mini-transcribe-realtime-26-02"


def build_client() -> Mistral:
    load_dotenv()
    api_key = os.getenv("MISTRAL_API_KEY")
    if not api_key:
        raise RuntimeError("MISTRAL_API_KEY is missing. Add it to your environment or .env file.")
    return Mistral(api_key=api_key)


def transcribe_file(client: Mistral, audio_path: Path, model: str, language: str | None) -> str:
    with audio_path.open("rb") as audio_file:
        response = client.audio.transcriptions.complete(
            model=model,
            file={"file_name": audio_path.name, "content": audio_file},
            language=language,
            timestamp_granularities=["segment"],
        )

    return response.text


async def wav_chunk_stream(audio_path: Path, chunk_ms: int) -> AsyncIterator[bytes]:
    with wave.open(str(audio_path), "rb") as wav_file:
        frame_rate = wav_file.getframerate()
        chunk_frames = max(1, int(frame_rate * (chunk_ms / 1000)))

        while True:
            chunk = wav_file.readframes(chunk_frames)
            if not chunk:
                break
            yield chunk
            await asyncio.sleep(chunk_ms / 1000)


async def transcribe_realtime(
    client: Mistral,
    audio_path: Path,
    model: str,
    chunk_ms: int,
    target_streaming_delay_ms: int,
) -> str:
    with wave.open(str(audio_path), "rb") as wav_file:
        sample_rate = wav_file.getframerate()
        sample_width = wav_file.getsampwidth()
        channels = wav_file.getnchannels()

    if sample_width != 2:
        raise ValueError(
            "Realtime pipeline currently expects 16-bit PCM WAV input (sample width = 2 bytes)."
        )

    if channels not in (1, 2):
        raise ValueError("Only mono or stereo WAV files are supported.")

    audio_format = AudioFormat(encoding="pcm_s16le", sample_rate=sample_rate)

    text_chunks: list[str] = []
    async for event in client.audio.realtime.transcribe_stream(
        audio_stream=wav_chunk_stream(audio_path, chunk_ms),
        model=model,
        audio_format=audio_format,
        target_streaming_delay_ms=target_streaming_delay_ms,
    ):
        event_type = getattr(event, "type", "unknown")

        if event_type == "transcription.text.delta":
            print(event.text, end="", flush=True)
            text_chunks.append(event.text)
        elif event_type == "transcription.segment":
            print(f"\n[segment {event.start:.2f}-{event.end:.2f}s] {event.text}")
        elif event_type == "transcription.done":
            print("\n\n[done]")
            if getattr(event, "text", None):
                return event.text
        elif event_type == "error":
            raise RuntimeError(f"Realtime transcription error: {event}")

    return "".join(text_chunks).strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="STT pipeline with Mistral Voxtral models")
    parser.add_argument("audio", type=Path, help="Path to an input audio file (.wav, .mp3, etc.)")
    parser.add_argument(
        "--mode",
        choices=["batch", "realtime"],
        default="realtime",
        help="batch = one-shot transcription API, realtime = websocket streaming API",
    )
    parser.add_argument("--model", default=DEFAULT_STT_MODEL, help="Mistral transcription model")
    parser.add_argument("--language", default=None, help="Optional language hint, e.g. en or fr")
    parser.add_argument("--chunk-ms", type=int, default=250, help="Realtime: chunk size to stream")
    parser.add_argument(
        "--target-streaming-delay-ms",
        type=int,
        default=300,
        help="Realtime: server latency/quality tradeoff target",
    )

    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if not args.audio.exists():
        raise FileNotFoundError(f"Audio file not found: {args.audio}")

    client = build_client()

    if args.mode == "batch":
        transcript = transcribe_file(client, args.audio, args.model, args.language)
        print(transcript)
        return

    if args.audio.suffix.lower() != ".wav":
        raise ValueError("Realtime mode currently requires a .wav file with PCM 16-bit audio.")

    transcript = asyncio.run(
        transcribe_realtime(
            client,
            args.audio,
            args.model,
            args.chunk_ms,
            args.target_streaming_delay_ms,
        )
    )
    if transcript:
        print(f"\nFinal transcript:\n{transcript}")


if __name__ == "__main__":
    main()
