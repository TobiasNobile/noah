#!/usr/bin/env python3
"""Quick smoke check for integration helpers."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from integration_adapter import stt_from_file, tts_to_base64


def main() -> int:
    parser = argparse.ArgumentParser(description="Smoke test STT/TTS integration functions")
    parser.add_argument("--audio", required=True, help="Path to finalized audio file for STT")
    parser.add_argument("--text", default=None, help="Optional text to force for TTS instead of STT transcript")
    args = parser.parse_args()

    stt = stt_from_file(args.audio)
    if not stt["ok"]:
        print(json.dumps({"stage": "stt", **stt}, indent=2))
        return 1

    text = args.text if args.text is not None else stt["text"]
    tts = tts_to_base64(text)
    if not tts["ok"]:
        print(json.dumps({"stage": "tts", **tts}, indent=2))
        return 1

    summary = {
        "ok": True,
        "transcript_length": len(stt["text"]),
        "audio_base64_length": len(tts["audio_base64"]),
        "mime": tts["mime"],
    }
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
