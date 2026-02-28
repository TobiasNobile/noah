from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import mean

import evaluate
import torch
from datasets import Dataset, DatasetDict, load_dataset
from unsloth import FastLanguageModel

from data_utils import build_prompt, normalize_to_chat


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate a Ministral+LoRA model on held-out split.")
    parser.add_argument("--dataset", default="sidfeels/visually-impaired-llm-assistance-dataset")
    parser.add_argument("--split", default="test", help="Split to evaluate. Falls back to train split if missing.")
    parser.add_argument("--model-name", default="mistralai/Ministral-3b-instruct")
    parser.add_argument(
        "--adapter-path",
        default="outputs/ministral3b-visually-impaired-lora/adapter",
        help="Path to the LoRA adapter output by training script.",
    )
    parser.add_argument("--max-seq-length", type=int, default=2048)
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--limit", type=int, default=200)
    parser.add_argument("--output-json", default="outputs/eval_results.json")
    return parser.parse_args()


def get_eval_dataset(dataset_id: str, split: str, limit: int) -> Dataset:
    raw = load_dataset(dataset_id)
    if isinstance(raw, DatasetDict):
        if split in raw:
            ds = raw[split]
        elif "test" in raw:
            ds = raw["test"]
        else:
            ds = raw["train"].train_test_split(test_size=0.1, seed=42)["test"]
    else:
        ds = raw.train_test_split(test_size=0.1, seed=42)["test"]

    if limit > 0:
        ds = ds.select(range(min(limit, len(ds))))
    return ds


def chunked(items: list[dict], size: int):
    for i in range(0, len(items), size):
        yield items[i : i + size]


def main() -> None:
    args = parse_args()

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=args.model_name,
        max_seq_length=args.max_seq_length,
        dtype=None,
        load_in_4bit=True,
    )
    model.load_adapter(args.adapter_path)
    FastLanguageModel.for_inference(model)

    rouge = evaluate.load("rouge")

    ds = get_eval_dataset(args.dataset, args.split, args.limit)

    records = []
    for row in ds:
        messages = normalize_to_chat(row)
        assistant_messages = [m["content"] for m in messages if m["role"] == "assistant" and m["content"]]
        if not assistant_messages:
            continue

        ref = assistant_messages[-1]
        prompt_messages = [m for m in messages if m["role"] != "assistant"]
        prompt = build_prompt(prompt_messages, tokenizer)
        records.append({"prompt": prompt, "reference": ref})

    predictions: list[str] = []
    references: list[str] = []

    for batch in chunked(records, args.batch_size):
        prompts = [item["prompt"] for item in batch]
        inputs = tokenizer(prompts, return_tensors="pt", padding=True, truncation=True).to(model.device)

        with torch.inference_mode():
            output_ids = model.generate(
                **inputs,
                max_new_tokens=args.max_new_tokens,
                do_sample=False,
                use_cache=True,
            )

        new_tokens = output_ids[:, inputs["input_ids"].shape[1] :]
        decoded = tokenizer.batch_decode(new_tokens, skip_special_tokens=True)

        for sample, pred in zip(batch, decoded):
            predictions.append(pred.strip())
            references.append(sample["reference"].strip())

    rouge_scores = rouge.compute(predictions=predictions, references=references)

    avg_pred_len = mean(len(x.split()) for x in predictions) if predictions else 0
    avg_ref_len = mean(len(x.split()) for x in references) if references else 0

    summary = {
        "num_samples": len(predictions),
        "rouge": rouge_scores,
        "avg_prediction_words": avg_pred_len,
        "avg_reference_words": avg_ref_len,
    }

    out_path = Path(args.output_json)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")

    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
