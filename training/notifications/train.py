"""Small deterministic, sparse logistic regression using only Python stdlib."""

import argparse
from collections import Counter
from contextlib import closing
import json
import math
from pathlib import Path
import random
import sqlite3

from training.notifications.features import FEATURE_VERSION, features, label

OUTPUT = Path(__file__).parent / "output"


def read_rows(path):
    path = Path(path)
    if path.suffix in {".db", ".sqlite", ".sqlite3"}:
        with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
            db.row_factory = sqlite3.Row
            return [dict(row) for row in db.execute("SELECT * FROM notification_log ORDER BY timeMs, id")]
    with path.open(encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def sigmoid(value):
    if value >= 0:
        return 1 / (1 + math.exp(-value))
    exp = math.exp(value)
    return exp / (1 + exp)


def predict(model, row):
    return sigmoid(model["bias"] + sum(model["weights"].get(key, 0.0) for key in features(row)))


def train(rows, *, include_kept=False, epochs=100, learning_rate=0.2,
          l2=0.001, max_features=4096, min_count=2):
    if epochs < 1 or max_features < 1 or min_count < 1:
        raise ValueError("epochs, max_features and min_count must be positive")
    if not math.isfinite(learning_rate) or learning_rate <= 0 or not math.isfinite(l2) or l2 < 0:
        raise ValueError("learning_rate must be positive and l2 nonnegative, both finite")
    examples = [(features(row), label(row, include_kept)) for row in rows]
    examples = [(keys, target) for keys, target in examples if target is not None]
    if {target for _, target in examples} != {0, 1}:
        raise ValueError("Training requires both keep and dismiss labels; mark important/noise in the app")
    counts = Counter(key for keys, _ in examples for key in keys)
    vocabulary = sorted((key for key, count in counts.items() if count >= min_count),
                        key=lambda key: (-counts[key], key))[:max_features]
    weights = dict.fromkeys(sorted(vocabulary), 0.0)
    examples = [([key for key in keys if key in weights], target) for keys, target in examples]
    bias = 0.0
    for _ in range(epochs):
        gradient = dict.fromkeys(weights, 0.0)
        bias_gradient = 0.0
        for keys, target in examples:
            error = sigmoid(bias + sum(weights[key] for key in keys)) - target
            bias_gradient += error
            for key in keys:
                gradient[key] += error
        scale = 1 / len(examples)
        bias -= learning_rate * bias_gradient * scale
        for key in weights:
            weights[key] -= learning_rate * (gradient[key] * scale + l2 * weights[key])
    return {"version": 1, "feature_version": FEATURE_VERSION, "bias": bias,
            "weights": weights, "threshold": 0.5}


def split_rows(rows, include_kept=False, seed=13):
    groups = {0: [], 1: []}
    for row in rows:
        target = label(row, include_kept)
        if target is not None:
            groups[target].append(row)
    if any(len(group) < 2 for group in groups.values()):
        raise ValueError("Need at least two labeled rows per class for separate training/evaluation")
    rng = random.Random(seed)
    training, evaluation = [], []
    for group in groups.values():
        rng.shuffle(group)
        count = max(1, len(group) // 5)
        evaluation.extend(group[:count])
        training.extend(group[count:])
    return training, evaluation


def evaluate(model, rows, include_kept=False):
    counts = {"true_keep": 0, "false_keep": 0, "true_dismiss": 0, "false_dismiss": 0}
    for row in rows:
        target = label(row, include_kept)
        if target is None:
            continue
        kept = predict(model, row) >= model["threshold"]
        key = ("true_" if kept == bool(target) else "false_") + ("keep" if kept else "dismiss")
        counts[key] += 1
    total = sum(counts.values())
    return {**counts, "rows": total,
            "accuracy": (counts["true_keep"] + counts["true_dismiss"]) / total if total else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Exported Room .db or JSONL rows")
    parser.add_argument("--output", type=Path, default=OUTPUT / "notification_model.json")
    parser.add_argument("--include-kept", action="store_true", help="Opt in to weak kept-at-post-time labels")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--max-features", type=int, default=4096)
    args = parser.parse_args()
    try:
        training, evaluation = split_rows(read_rows(args.input), args.include_kept)
        model = train(training, include_kept=args.include_kept, epochs=args.epochs, max_features=args.max_features)
        metrics = evaluate(model, evaluation, args.include_kept)
    except (ValueError, OSError, sqlite3.Error) as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(model, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({"training_rows": len(training), "held_out": metrics, "output": str(args.output)}))


if __name__ == "__main__":
    main()
