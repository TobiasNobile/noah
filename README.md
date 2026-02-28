# Noah: simple realtime STT pipeline

This repo now uses a **simple realtime speech-to-text flow** based on the Mistral realtime transcription pattern (microphone stream over websocket).

## What it does

- Captures microphone audio as PCM16 mono using PyAudio.
- Streams chunks to Mistral realtime transcription.
- Prints transcript deltas live.

Main script: `stt_pipeline.py` (entrypoint remains `main.py`).

## Setup

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Create a `.env` file:

```bash
MISTRAL_API_KEY=your_key_here
```

## Run realtime microphone transcription

```bash
python main.py
```

With options:

```bash
python main.py --model voxtral-mini-transcribe-realtime-2602 --sample-rate 16000 --chunk-duration-ms 480
```

## Notes

- Press `Ctrl+C` to stop.
- Requires a working microphone.
- Requires `PyAudio` installed in your environment.
- If you get a model error, switch `--model` to one available for your account.
