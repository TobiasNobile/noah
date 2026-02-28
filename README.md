# Noah: assistive voice + vision architecture

## Use cases

- Enable a visually impaired/blind person to find their way around their environment and complex places.
- Enable illiterate or functionally illiterate people to read a document or text.

## Speech-to-Text (STT) pipeline (first building block)

This repository includes an STT pipeline using Mistral Voxtral APIs with mode-specific defaults:

- Realtime default model: `voxtral-mini-transcribe`
- Batch/offline default model: `voxtral-mini-latest`
- Script: `stt_pipeline.py`
- Entrypoint alias: `main.py`

Reference docs used:
- Realtime transcription: https://docs.mistral.ai/capabilities/audio_transcription/realtime_transcription
- Offline transcription: https://docs.mistral.ai/capabilities/audio_transcription/offline_transcription

## 1) Setup

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Create a `.env` file:

```bash
MISTRAL_API_KEY=your_key_here
```

## 2) Realtime transcription (WebSocket)

Realtime mode accepts a WAV file and normalizes audio to PCM16 mono @16kHz before streaming.
To maximize compatibility, optional realtime session update params are skipped by default.

```bash
python main.py path/to/audio.wav --mode realtime
```

Realtime options:

- `--chunk-ms` (default `250`)
- `--model` (optional override)
- `--target-streaming-delay-ms` (optional; only set if you need to force session update)

## 3) Batch / offline transcription (one-shot)

Batch mode sends a complete audio file using the offline transcription endpoint.

```bash
python main.py path/to/audio.wav --mode batch
```

Batch options:

- `--language en` (or `fr`, etc.) as an optional language hint
- `--model` (optional override)

## Troubleshooting

### `HTTP 401` / `Unauthorized`

- verify `MISTRAL_API_KEY` is set and does not contain extra quotes/spaces,
- ensure the key is active and has access to the transcription model you selected.

### `Invalid model` / `does not exist for router`

This means your selected model doesn't match the endpoint mode or is not enabled for your account.

- for realtime mode, use a realtime model (default in this script: `voxtral-mini-transcribe`),
- for batch mode, use a batch/offline model (default in this script: `voxtral-mini-latest`).

### Realtime `1008 policy violation`

This usually means realtime session settings/account policy were rejected by the server.

- first try without forcing session update params:

```bash
python main.py ./audio.wav --mode realtime --model voxtral-mini-transcribe
```

- if needed, then test with explicit delay setting:

```bash
python main.py ./audio.wav --mode realtime --model voxtral-mini-transcribe --target-streaming-delay-ms 300
```
