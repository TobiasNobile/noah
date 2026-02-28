import matplotlib.pyplot as plt
import numpy as np
import json
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
METRICS_PATH = ROOT_DIR / "assets" / "outputs" / "metrics.json"

with open(METRICS_PATH) as f:
    data = json.load(f)

docs = [d for d in data if d["label"] == "DOCUMENT"]
envs = [d for d in data if d["label"] == "ENVIRONNEMENT"]

metrics = ["entropy",  "h_edge_ratio"]
seuils  = [6.0, 1.0]
labels  = ["Entropie (< 6 → Document)", "H/V Edge Ratio (0.8–1.2 → Document)"]

fig, axes = plt.subplots(1, 2, figsize=(15, 4))
fig.suptitle("Distribution des métriques par classe", fontsize=14, fontweight="bold")

for ax, metric, seuil, label in zip(axes, metrics, seuils, labels):
    d_vals = [d[metric] for d in docs]
    e_vals = [d[metric] for d in envs]
    
    bins = np.linspace(
        min(d_vals + e_vals), 
        max(d_vals + e_vals), 
        20
    )
    
    ax.hist(d_vals, bins=bins, alpha=0.6, color="#3B82F6", label=f"Document ({len(docs)})")
    ax.hist(e_vals, bins=bins, alpha=0.6, color="#F97316", label=f"Environnement ({len(envs)})")
    ax.axvline(x=seuil, color="red", linestyle="--", linewidth=1.5, label=f"Seuil: {seuil}")
    
    # Zone de chevauchement → là où il faut ajuster le seuil
    ax.set_title(label)
    ax.legend(fontsize=8)
    ax.set_xlabel("Valeur")
    ax.set_ylabel("Nb images")

plt.tight_layout()
plt.savefig("backend-ai/metrics_analysis.png", dpi=150)
plt.show()