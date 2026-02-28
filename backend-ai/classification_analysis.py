import json
from pathlib import Path
from collections import defaultdict


METRICS_PATH = Path("assets/outputs/metrics.json")


def load_results(path: Path) -> list[dict]:
    with open(path, "r") as f:
        return json.load(f)


def compute_metrics(results: list[dict]) -> dict:
    classes = sorted({r["true_label"] for r in results})

    # Compteurs par classe
    tp = defaultdict(int)
    fp = defaultdict(int)
    fn = defaultdict(int)

    for r in results:
        pred = r["label_pred"]
        true = r["true_label"]
        if pred == true:
            tp[true] += 1
        else:
            fp[pred] += 1
            fn[true] += 1

    total = len(results)
    correct = sum(1 for r in results if r["correct"])
    accuracy = correct / total if total > 0 else 0

    per_class = {}
    for cls in classes:
        precision = tp[cls] / (tp[cls] + fp[cls]) if (tp[cls] + fp[cls]) > 0 else 0
        recall    = tp[cls] / (tp[cls] + fn[cls]) if (tp[cls] + fn[cls]) > 0 else 0
        f1        = (2 * precision * recall / (precision + recall)
                     if (precision + recall) > 0 else 0)
        per_class[cls] = {
            "tp": tp[cls],
            "fp": fp[cls],
            "fn": fn[cls],
            "precision": round(precision, 4),
            "recall":    round(recall, 4),
            "f1":        round(f1, 4),
        }

    # Macro average
    macro_precision = sum(v["precision"] for v in per_class.values()) / len(classes)
    macro_recall    = sum(v["recall"]    for v in per_class.values()) / len(classes)
    macro_f1        = sum(v["f1"]        for v in per_class.values()) / len(classes)

    return {
        "total": total,
        "correct": correct,
        "accuracy": round(accuracy, 4),
        "per_class": per_class,
        "macro": {
            "precision": round(macro_precision, 4),
            "recall":    round(macro_recall, 4),
            "f1":        round(macro_f1, 4),
        },
    }


def print_report(metrics: dict) -> None:
    print("=" * 50)
    print(f"  Total images : {metrics['total']}")
    print(f"  Correct      : {metrics['correct']}")
    print(f"  Accuracy     : {metrics['accuracy'] * 100:.2f}%")
    print("=" * 50)

    print(f"\n{'Classe':<15} {'Precision':>10} {'Recall':>10} {'F1':>10}  TP  FP  FN")
    print("-" * 60)
    for cls, v in metrics["per_class"].items():
        print(f"{cls:<15} {v['precision']:>10.4f} {v['recall']:>10.4f} {v['f1']:>10.4f}"
              f"  {v['tp']:>3} {v['fp']:>3} {v['fn']:>3}")

    print("-" * 60)
    m = metrics["macro"]
    print(f"{'MACRO':<15} {m['precision']:>10.4f} {m['recall']:>10.4f} {m['f1']:>10.4f}")
    print("=" * 50)


def print_errors(results: list[dict]) -> None:
    errors = [r for r in results if not r["correct"]]
    if not errors:
        print("\nAucune erreur de classification.")
        return

    print(f"\nErreurs ({len(errors)}) :")
    print(f"  {'Fichier':<35} {'Prédit':<15} {'Vrai':<15} {'Entropy':>8} {'H-ratio':>8}")
    print("  " + "-" * 85)
    for r in errors:
        print(f"  {r['file']:<35} {r['label_pred']:<15} {r['true_label']:<15}"
              f" {r['entropy']:>8.3f} {r['h_edge_ratio']:>8.3f}")


if __name__ == "__main__":
    results = load_results(METRICS_PATH)
    metrics = compute_metrics(results)
    print_report(metrics)
    print_errors(results)

    # Sauvegarde optionnelle du rapport
    output_path = Path("assets/outputs/evaluation_report.json")
    with open(output_path, "w") as f:
        json.dump(metrics, f, indent=2)
    print(f"\nRapport sauvegardé dans {output_path}")