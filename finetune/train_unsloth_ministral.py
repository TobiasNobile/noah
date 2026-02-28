from __future__ import annotations

import argparse
from pathlib import Path

from datasets import DatasetDict, load_dataset
from transformers import TrainingArguments
from trl import SFTTrainer
from unsloth import FastLanguageModel, is_bfloat16_supported

from data_utils import as_training_text


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fine-tune Ministral 3B with Unsloth (QLoRA) on an accessibility dataset."
    )
    parser.add_argument(
        "--dataset",
        default="sidfeels/visually-impaired-llm-assistance-dataset",
        help="Hugging Face dataset ID or local dataset path.",
    )
    parser.add_argument("--split", default="train", help="Dataset split to use when dataset has a single split.")
    parser.add_argument(
        "--model-name",
        default="mistralai/Ministral-3b-instruct",
        help="Base instruct/chat model checkpoint.",
    )
    parser.add_argument("--output-dir", default="outputs/ministral3b-visually-impaired-lora")
    parser.add_argument("--max-seq-length", type=int, default=2048)
    parser.add_argument("--train-batch-size", type=int, default=4)
    parser.add_argument("--eval-batch-size", type=int, default=4)
    parser.add_argument("--grad-accum", type=int, default=4)
    parser.add_argument("--epochs", type=float, default=2.0)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--warmup-ratio", type=float, default=0.05)
    parser.add_argument("--weight-decay", type=float, default=0.01)
    parser.add_argument("--test-size", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--lora-r", type=int, default=16)
    parser.add_argument("--lora-alpha", type=int, default=32)
    parser.add_argument("--lora-dropout", type=float, default=0.05)
    parser.add_argument("--save-steps", type=int, default=100)
    parser.add_argument("--eval-steps", type=int, default=100)
    parser.add_argument("--logging-steps", type=int, default=10)
    return parser.parse_args()


def load_and_prepare_dataset(dataset_id: str, split: str, test_size: float, seed: int) -> DatasetDict:
    raw = load_dataset(dataset_id)

    if isinstance(raw, DatasetDict):
        if "train" in raw and "test" in raw:
            return DatasetDict(train=raw["train"], test=raw["test"])
        if "train" in raw:
            return raw["train"].train_test_split(test_size=test_size, seed=seed)
        if split in raw:
            return raw[split].train_test_split(test_size=test_size, seed=seed)

    return raw.train_test_split(test_size=test_size, seed=seed)


def main() -> None:
    args = parse_args()

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=args.model_name,
        max_seq_length=args.max_seq_length,
        dtype=None,
        load_in_4bit=True,
    )

    model = FastLanguageModel.get_peft_model(
        model,
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=args.lora_dropout,
        target_modules=[
            "q_proj",
            "k_proj",
            "v_proj",
            "o_proj",
            "gate_proj",
            "up_proj",
            "down_proj",
        ],
        use_rslora=True,
        use_gradient_checkpointing="unsloth",
        random_state=args.seed,
    )

    split_ds = load_and_prepare_dataset(args.dataset, args.split, args.test_size, args.seed)

    train_ds = split_ds["train"].map(
        lambda row: as_training_text(row, tokenizer),
        remove_columns=split_ds["train"].column_names,
        desc="Formatting train examples",
    )
    eval_ds = split_ds["test"].map(
        lambda row: as_training_text(row, tokenizer),
        remove_columns=split_ds["test"].column_names,
        desc="Formatting eval examples",
    )

    train_ds = train_ds.filter(lambda row: bool(row["text"].strip()), desc="Dropping empty train rows")
    eval_ds = eval_ds.filter(lambda row: bool(row["text"].strip()), desc="Dropping empty eval rows")

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=train_ds,
        eval_dataset=eval_ds,
        dataset_text_field="text",
        max_seq_length=args.max_seq_length,
        args=TrainingArguments(
            output_dir=str(output_dir),
            per_device_train_batch_size=args.train_batch_size,
            per_device_eval_batch_size=args.eval_batch_size,
            gradient_accumulation_steps=args.grad_accum,
            num_train_epochs=args.epochs,
            learning_rate=args.learning_rate,
            warmup_ratio=args.warmup_ratio,
            weight_decay=args.weight_decay,
            logging_steps=args.logging_steps,
            save_steps=args.save_steps,
            eval_steps=args.eval_steps,
            evaluation_strategy="steps",
            save_strategy="steps",
            bf16=is_bfloat16_supported(),
            fp16=not is_bfloat16_supported(),
            seed=args.seed,
            report_to="none",
        ),
    )

    trainer.train()
    trainer.save_model(str(output_dir / "adapter"))
    tokenizer.save_pretrained(str(output_dir / "adapter"))

    metrics = trainer.evaluate()
    print("Final eval metrics:", metrics)


if __name__ == "__main__":
    main()
