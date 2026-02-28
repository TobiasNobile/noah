# Noah: assistive voice + vision architecture

## Use cases

- Enable a visually impaired/blind person to find their way around their environment and complex places.
- Enable illiterate or functionally illiterate people to read a document or text.

## Speech-to-Text (STT) pipeline (first building block)

This repository includes an STT pipeline using Mistral Voxtral APIs with mode-specific defaults:

- Realtime default model: `voxtral-mini-transcribe-realtime-26-02`
- Batch/offline default model: `voxtral-mini-latest`
- Script: `stt_pipeline.py`
- Entrypoint alias: `main.py`

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

Realtime mode streams PCM 16-bit WAV chunks to the realtime endpoint.

```bash
python main.py path/to/audio.wav --mode realtime
```

Realtime options:

- `--chunk-ms` (default `250`)
- `--target-streaming-delay-ms` (default `300`)
- `--model` (optional override)

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

### `Invalid model` / `invalid_model`

This means your selected model doesn't match the endpoint mode.

- for realtime mode, use a realtime model (default in this script: `voxtral-mini-transcribe-realtime-26-02`),
- for batch mode, use a batch/offline model (default in this script: `voxtral-mini-latest`).

You can always override explicitly:

```bash
python main.py ./audio.wav --mode batch --model voxtral-mini-latest
python main.py ./audio.wav --mode realtime --model voxtral-mini-transcribe-realtime-26-02
```
