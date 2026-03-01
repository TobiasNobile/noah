# Noah: simple STT and TTS pipeline

This repo provides:

- **CLI modes** for local testing (realtime mic STT, batch file STT, and local TTS playback)
- **Importable integration helpers** for Flask/FastAPI (`integration_adapter.py`)

Main script: `stt_pipeline.py`.

## Setup

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Create a `.env` file with your API keys:

```bash
MISTRAL_API_KEY="your_mistral_api_key"
ELEVENLABS_API_KEY="your_elevenlabs_api_key"
```

## Integration for Flask/FastAPI

Use these functions from backend routes:

- `stt_from_file(path: str) -> dict`
- `tts_to_base64(text: str) -> dict`
- `tts_to_wav_file(text: str, output_path: str) -> dict`

Example:

```python
from integration_adapter import stt_from_file, tts_to_base64, tts_to_wav_file

stt = stt_from_file("temp/cache/audio/<uuid>/last.wav")
if not stt["ok"]:
    return stt

answer_text = "...LLM answer..."
tts = tts_to_base64(answer_text)
if not tts["ok"]:
    return {"answer": answer_text, **tts}

wav = tts_to_wav_file(answer_text, "./temp/answer.wav")

return {
    "ok": True,
    "transcript": stt["text"],
    "answer": answer_text,
    "answer_audio_base64": tts["audio_base64"],
    "answer_audio_mime": tts["mime"],
    "answer_audio_wav_path": wav.get("wav_path") if wav.get("ok") else None,
}
```

## Realtime microphone transcription (CLI)

```bash
python stt_pipeline.py --mode realtime
```

Options:

```bash
python stt_pipeline.py --mode realtime --model voxtral-mini-transcribe-realtime-2602 --sample-rate 16000 --chunk-duration-ms 480
```

## Batch file transcription (CLI)

```bash
python stt_pipeline.py ./audio.wav --mode batch
```

Options:

```bash
python stt_pipeline.py ./audio.wav --mode batch --model voxtral-mini-latest --language en
```

## Text-to-Speech (CLI)

This mode uses ElevenLabs to convert text to speech and play it locally.

```bash
python stt_pipeline.py --mode tts
```

After running the command, type text and press Enter to hear it.

## Smoke test for integration

```bash
python -m scripts.smoke_integration --text "Hello from Noah" --save-wav ./out/hello.wav
# or with STT + TTS: python -m scripts.smoke_integration --audio ./audio.wav --save-wav ./out/from_audio.wav
# direct script execution is also supported:
# python scripts/smoke_integration.py --text "Hello" --save-wav ./out/hello.wav
```

## Notes

- Press `Ctrl+C` to stop realtime and TTS CLI modes.
- Realtime mode and local TTS playback require `PyAudio`.
- Backend integration via `integration_adapter.py` does **not** require speaker playback.
