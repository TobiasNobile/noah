# Noah: simple STT and TTS pipeline

This repo now provides a simple CLI with three modes:

- **Realtime** from microphone (WebSocket)
- **Batch** from audio file (offline API)
- **Text-to-Speech** from text input

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

## Realtime microphone transcription

```bash
python stt_pipeline.py --mode realtime
```

Options:

```bash
python stt_pipeline.py --mode realtime --model voxtral-mini-transcribe-realtime-2602 --sample-rate 16000 --chunk-duration-ms 480
```

## Batch file transcription

```bash
python stt_pipeline.py ./audio.wav --mode batch
```

Options:

```bash
python stt_pipeline.py ./audio.wav --mode batch --model voxtral-mini-latest --language en
```

## Text-to-Speech

This mode uses the Eleven Labs API to convert text to speech.

```bash
python stt_pipeline.py --mode tts
```

After running the command, type any text and press Enter to hear it spoken.


## Notes

- Press `Ctrl+C` to stop realtime and tts modes.
- Realtime mode requires a working microphone.
- Realtime and TTS modes require `PyAudio` installed in your environment.
