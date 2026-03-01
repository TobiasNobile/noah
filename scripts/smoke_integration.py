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



def main() -> int:
    parser = argparse.ArgumentParser(description="Smoke test STT/TTS integration functions")
    parser.add_argument("--audio", default=None, help="Optional path to finalized audio file for STT")
    parser.add_argument("--text", default=None, help="Optional text input for TTS")
    parser.add_argument(
        "--save-wav",
        default=None,
        help="Optional output WAV path. If provided, TTS audio is also saved as .wav.",
    )
    args = parser.parse_args()

    from integration_adapter import stt_from_file, tts_to_base64, tts_to_wav_file

    if args.audio is None and args.text is None:
        print(json.dumps({
            "ok": False,
            "error": "Provide at least one input: --text or --audio",
        }, indent=2))
        return 1

    transcript = None
    if args.audio:
        stt = stt_from_file(args.audio)
        if not stt["ok"]:
            print(json.dumps({"stage": "stt", **stt}, indent=2))
            return 1
        transcript = stt["text"]

    text = args.text if args.text is not None else transcript
    if not text:
        print(json.dumps({"ok": False, "error": "No text available for TTS"}, indent=2))
        return 1

    tts = tts_to_base64(text)
    if not tts["ok"]:
        print(json.dumps({"stage": "tts", **tts}, indent=2))
        return 1

    response = {
        "ok": True,
        "text_length": len(text),
        "audio_base64_length": len(tts["audio_base64"]),
        "mime": tts["mime"],
    }

    if args.save_wav:
        wav = tts_to_wav_file(text, args.save_wav)
        if not wav["ok"]:
            print(json.dumps({"stage": "tts_wav", **wav}, indent=2))
            return 1
        response["wav_path"] = wav["wav_path"]

    if transcript is not None:
        response["transcript_length"] = len(transcript)

    print(json.dumps(response, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
