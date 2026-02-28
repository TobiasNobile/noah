# Noah: simple STT pipeline

This repo now provides a simple STT CLI with two modes:

- **Realtime** from microphone (WebSocket)
- **Batch** from audio file (offline API)

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

## Realtime microphone transcription

```bash
python main.py --mode realtime
```

Options:

```bash
python main.py --mode realtime --model voxtral-mini-transcribe-realtime-2602 --sample-rate 16000 --chunk-duration-ms 480
```

## Batch file transcription (kept)

```bash
python main.py ./audio.wav --mode batch
```

Options:

```bash
python main.py ./audio.wav --mode batch --model voxtral-mini-latest --language en
```

## Notes

- Press `Ctrl+C` to stop realtime mode.
- Realtime mode requires a working microphone.
- Realtime mode requires `PyAudio` installed in your environment.
