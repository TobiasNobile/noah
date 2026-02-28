from __future__ import annotations

from typing import Any


def _to_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    return str(value).strip()


def normalize_to_chat(example: dict[str, Any]) -> list[dict[str, str]]:
    """Normalize a record from common instruction/chat schemas into chat messages."""
    if "messages" in example and isinstance(example["messages"], list) and example["messages"]:
        messages = []
        for message in example["messages"]:
            role = _to_text(message.get("role", "user")).lower() or "user"
            content = _to_text(message.get("content", ""))
            if content:
                messages.append({"role": role, "content": content})
        if messages:
            return messages

    system = _to_text(
        example.get("system")
        or example.get("system_prompt")
        or example.get("context")
    )

    user = _to_text(
        example.get("prompt")
        or example.get("question")
        or example.get("instruction")
        or example.get("input")
        or example.get("user")
    )

    assistant = _to_text(
        example.get("response")
        or example.get("answer")
        or example.get("output")
        or example.get("assistant")
    )

    if not user and "input" in example and "instruction" in example:
        instruction = _to_text(example.get("instruction"))
        model_input = _to_text(example.get("input"))
        user = "\n".join(part for part in [instruction, model_input] if part)

    messages: list[dict[str, str]] = []
    if system:
        messages.append({"role": "system", "content": system})
    if user:
        messages.append({"role": "user", "content": user})
    if assistant:
        messages.append({"role": "assistant", "content": assistant})

    if not messages:
        raise ValueError(f"Could not normalize record with keys: {list(example.keys())}")

    return messages


def as_training_text(example: dict[str, Any], tokenizer: Any) -> dict[str, str]:
    messages = normalize_to_chat(example)

    has_assistant = any(message["role"] == "assistant" and message["content"] for message in messages)
    if not has_assistant:
        return {"text": ""}

    text = tokenizer.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=False,
    )
    return {"text": text}


def build_prompt(messages: list[dict[str, str]], tokenizer: Any) -> str:
    return tokenizer.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True,
    )
