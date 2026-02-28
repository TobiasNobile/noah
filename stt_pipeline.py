import argparse
import asyncio
import audioop
import os
import wave
from pathlib import Path
from typing import AsyncIterator

from dotenv import find_dotenv, load_dotenv
from mistralai import Mistral
from mistralai.models import AudioFormat

# Realtime model aligned with Mistral realtime transcription docs.
DEFAULT_REALTIME_MODEL = "voxtral-mini-transcribe"
DEFAULT_BATCH_MODEL = "voxtral-mini-latest"
DEFAULT_REALTIME_SAMPLE_RATE = 16000


class STTPipelineError(RuntimeError):
    """Raised when the STT pipeline fails with an actionable message."""


def build_client() -> Mistral:
    dotenv_path = find_dotenv(usecwd=True)
    load_dotenv(dotenv_path if dotenv_path else None)

    api_key = (os.getenv("MISTRAL_API_KEY") or "").strip().strip('"').strip("'")
    if not api_key:
        raise STTPipelineError(
            "MISTRAL_API_KEY is missing. Add it to your shell env or .env file and retry."
        )

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
        raise STTPipelineError(_explain_api_error(exc, model, mode="batch")) from exc


def load_normalized_wav(audio_path: Path, target_rate: int = DEFAULT_REALTIME_SAMPLE_RATE) -> bytes:
    """Read WAV file and normalize to PCM16 mono at target_rate for realtime STT."""
    with wave.open(str(audio_path), "rb") as wav_file:
        source_rate = wav_file.getframerate()
        source_width = wav_file.getsampwidth()
        source_channels = wav_file.getnchannels()
        raw = wav_file.readframes(wav_file.getnframes())

    if source_channels not in (1, 2):
        raise STTPipelineError("Realtime mode only supports mono or stereo WAV files.")

    # Convert to PCM16 if needed.
    if source_width != 2:
        raw = audioop.lin2lin(raw, source_width, 2)

    # Downmix stereo to mono.
    if source_channels == 2:
        raw = audioop.tomono(raw, 2, 0.5, 0.5)

    # Resample to a safe realtime rate.
    if source_rate != target_rate:
        raw, _ = audioop.ratecv(raw, 2, 1, source_rate, target_rate, None)

    return raw


async def pcm_chunk_stream(pcm_bytes: bytes, sample_rate: int, chunk_ms: int) -> AsyncIterator[bytes]:
    bytes_per_second = sample_rate * 2  # mono, PCM16
    chunk_size = max(2, int(bytes_per_second * (chunk_ms / 1000)))
    if chunk_size % 2 != 0:
        chunk_size += 1

    for i in range(0, len(pcm_bytes), chunk_size):
        yield pcm_bytes[i : i + chunk_size]
        await asyncio.sleep(chunk_ms / 1000)


def _explain_api_error(exc: Exception, model: str, mode: str) -> str:
    msg = str(exc)
    lowered = msg.lower()

    if "401" in lowered or "unauthorized" in lowered:
        return (
            f"{mode.capitalize()} STT authentication failed (HTTP 401). "
            "Check MISTRAL_API_KEY (no extra quotes/spaces), ensure the key is active, "
            f"and confirm access to model '{model}'. Original error: {msg}"
        )

    if "1008" in lowered or "policy violation" in lowered:
        return (
            f"{mode.capitalize()} STT websocket policy violation (1008). "
            "This usually means realtime session settings or account policy were rejected. "
            "This script sends normalized PCM16 mono @16kHz and avoids optional session updates by default. "
            f"Verify realtime access for model '{model}'. Original error: {msg}"
        )

    if "invalid_model" in lowered or "invalid model" in lowered or "does not exist for router" in lowered:
        suggested = DEFAULT_BATCH_MODEL if mode == "batch" else DEFAULT_REALTIME_MODEL
        return (
            f"{mode.capitalize()} STT received an invalid/unavailable model error for '{model}'. "
            f"Try a {mode} compatible model (default in this script: '{suggested}') "
            "or update to a model currently available for your account. "
            f"Original error: {msg}"
        )

    return f"{mode.capitalize()} STT failed: {msg}"


async def transcribe_realtime(
    client: Mistral,
    audio_path: Path,
    model: str,
    chunk_ms: int,
    target_streaming_delay_ms: int | None,
) -> str:
    normalized_pcm = load_normalized_wav(audio_path, target_rate=DEFAULT_REALTIME_SAMPLE_RATE)
    audio_stream = pcm_chunk_stream(normalized_pcm, DEFAULT_REALTIME_SAMPLE_RATE, chunk_ms)

    stream_kwargs = {
        "audio_stream": audio_stream,
        "model": model,
    }

    # Only send optional session update fields when explicitly requested.
    if target_streaming_delay_ms is not None:
        stream_kwargs["audio_format"] = AudioFormat(
            encoding="pcm_s16le", sample_rate=DEFAULT_REALTIME_SAMPLE_RATE
        )
        stream_kwargs["target_streaming_delay_ms"] = target_streaming_delay_ms

    try:
        text_chunks: list[str] = []
        async for event in client.audio.realtime.transcribe_stream(**stream_kwargs):
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
                raise STTPipelineError(f"Realtime transcription error: {event}")

        return "".join(text_chunks).strip()
    except Exception as exc:
        raise STTPipelineError(_explain_api_error(exc, model, mode="realtime")) from exc


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="STT pipeline with Mistral Voxtral models")
    parser.add_argument("audio", type=Path, help="Path to an input audio file (.wav, .mp3, etc.)")
    parser.add_argument(
        "--mode",
        choices=["batch", "realtime"],
        default="realtime",
        help="batch = one-shot transcription API, realtime = websocket streaming API",
    )
    parser.add_argument(
        "--model",
        default=None,
        help=(
            "Mistral transcription model. If omitted, defaults to "
            f"'{DEFAULT_REALTIME_MODEL}' in realtime mode and '{DEFAULT_BATCH_MODEL}' in batch mode."
        ),
    )
    parser.add_argument("--language", default=None, help="Optional language hint, e.g. en or fr")
    parser.add_argument("--chunk-ms", type=int, default=250, help="Realtime: chunk size to stream")
    parser.add_argument(
        "--target-streaming-delay-ms",
        type=int,
        default=None,
        help=(
            "Realtime: optional server latency/quality tradeoff target. "
            "If unset, session update is skipped to maximize compatibility."
        ),
    )

    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if not args.audio.exists():
        raise FileNotFoundError(f"Audio file not found: {args.audio}")

    client = build_client()

    model = args.model or (DEFAULT_BATCH_MODEL if args.mode == "batch" else DEFAULT_REALTIME_MODEL)

    if args.mode == "batch":
        transcript = transcribe_file(client, args.audio, model, args.language)
        print(transcript)
        return

    if args.audio.suffix.lower() != ".wav":
        raise STTPipelineError("Realtime mode currently requires a .wav file as input.")

    transcript = asyncio.run(
        transcribe_realtime(
            client,
            args.audio,
            model,
            args.chunk_ms,
            args.target_streaming_delay_ms,
        )
    )
    if transcript:
        print(f"\nFinal transcript:\n{transcript}")


if __name__ == "__main__":
    main()
