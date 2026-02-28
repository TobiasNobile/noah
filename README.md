# Noah: assistive voice + vision architecture

## Use cases

- Enable a visually impaired/blind person to find their way around their environment and complex places.
- Enable illiterate or functionally illiterate people to read a document or text.

## Speech-to-Text (STT) pipeline (first building block)

This repository now includes a first STT pipeline using Mistral's Voxtral realtime transcription model:

- Model default: `voxtral-mini-transcribe-realtime-26-02`
- Script: `stt_pipeline.py`
- Entrypoint alias: `main.py`

### 1) Setup

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Create a `.env` file:

```bash
MISTRAL_API_KEY=your_key_here
```

### 2) Realtime transcription (WebSocket)

Realtime mode currently streams PCM 16-bit WAV audio in chunks to the Voxtral realtime API.

```bash
python main.py path/to/audio.wav --mode realtime
```

Options:

- `--chunk-ms` (default `250`): size of each audio chunk sent to API.
- `--target-streaming-delay-ms` (default `300`): latency/quality tradeoff.
- `--model` to override model.

### 3) Batch transcription (one-shot)

If you want to transcribe a whole file in one API request:

```bash
python main.py path/to/audio.mp3 --mode batch
```

Options:

- `--language en` (or `fr`, etc.) as an optional language hint.

## Notes for next step in your architecture

This STT output is ready to feed into your `Agent` box from your diagram. The natural next step is:

1. Stream transcript deltas into the agent loop.
2. Keep short-term context per call/session.
3. Route final transcript turns into LLM + memory/RAG.


### Troubleshooting realtime `HTTP 401`

If realtime mode fails with `server rejected WebSocket connection: HTTP 401`:

- verify `MISTRAL_API_KEY` is set and does not contain extra quotes/spaces,
- ensure the key is active and has access to realtime transcription,
- retry with explicit env export before running:

```bash
export MISTRAL_API_KEY=your_key_here
python main.py ./your.wav --mode realtime
```

The script now surfaces a clearer action-oriented error message for this case.
