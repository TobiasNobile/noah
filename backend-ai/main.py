import json
from pathlib import Path

from core.classifier import classify_image

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
ASSETS_DIR = ROOT_DIR / "assets"

results = []
for path in ASSETS_DIR.glob("*.jpg"):
    results.append(classify_image(path))

with open("metrics.json", "w") as f:
    json.dump(results, f, indent=2)

print(f"{len(results)} classified images")