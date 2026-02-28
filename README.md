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

## Fine-tuning Ministral 3B with Unsloth

These scripts help you fine-tune and evaluate `mistralai/Ministral-3b-instruct` with the dataset:

- `sidfeels/visually-impaired-llm-assistance-dataset`

### Install fine-tuning dependencies

```bash
pip install -r finetune/requirements-finetune.txt
```

### Train (QLoRA)

```bash
python finetune/train_unsloth_ministral.py \
  --dataset sidfeels/visually-impaired-llm-assistance-dataset \
  --model-name mistralai/Ministral-3b-instruct \
  --output-dir outputs/ministral3b-visually-impaired-lora
```

Notes:
- The training script normalizes common dataset schemas (`messages`, `prompt/response`, `instruction/input/output`) into chat format.
- If the dataset has no predefined test split, it creates one with `--test-size` (default `0.1`).

### Evaluate adapter

```bash
python finetune/evaluate_unsloth_ministral.py \
  --dataset sidfeels/visually-impaired-llm-assistance-dataset \
  --model-name mistralai/Ministral-3b-instruct \
  --adapter-path outputs/ministral3b-visually-impaired-lora/adapter \
  --output-json outputs/eval_results.json
```

The evaluation script:
- builds prompts from each sample,
- generates deterministic answers,
- computes ROUGE scores,
- writes a JSON report.

## Notes

- Press `Ctrl+C` to stop realtime mode.
- Realtime mode requires a working microphone.
- Realtime mode requires `PyAudio` installed in your environment.
