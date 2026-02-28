import matplotlib.pyplot as plt
import numpy as np
import json
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
ASSETS_DIR = ROOT_DIR / "assets" / "outputs"

with open("assets/outputs/metrics.json") as f:
    data = json.load(f)

docs = [d for d in data if d["label"] == "DOCUMENT"]
envs = [d for d in data if d["label"] == "ENVIRONNEMENT"]

metrics = ["laplacian_variance", "edge_density", "brightness_std"]
seuils  = [500, 0.12, 60]
labels  = ["Laplacian Variance", "Edge Density", "Brightness Std"]

fig, axes = plt.subplots(1, 3, figsize=(15, 4))
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