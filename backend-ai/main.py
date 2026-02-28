import json
from pathlib import Path

from core.classifier import classify_image

ROOT_DIR = Path(__file__).resolve().parent.parent
ASSETS_DIR = ROOT_DIR / "assets" / "images"

results = []
for path in ASSETS_DIR.rglob("*.jpg"):
    result = classify_image(path)
    result["true_label"] = path.parent.name.upper()
    result["correct"]    = result["label_pred"] == result["true_label"]
    results.append(result)

with open("assets/outputs/metrics.json", "w") as f:
    json.dump(results, f, indent=2)

print(f"{len(results)} classified images")