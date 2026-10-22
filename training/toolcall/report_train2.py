#!/usr/bin/env python3
"""Compare complete paired round-two evaluation results."""
import argparse
import json
from pathlib import Path


def read_rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    counts = {"old": 45, "round1": 1000,
              "real": len(read_rows(Path(__file__).with_name("cases_real.jsonl")))}
    pairs = {}
    try:
        for name, count in counts.items():
            old, new = [read_rows(args.results / f"eval_{model}_{name}.jsonl")
                        for model in ("v2", "v3")]
            if len(old) != count or len(new) != count:
                raise ValueError(f"{name}: expected {count} rows per model, got {len(old)}/{len(new)}")
            for index, (before, after) in enumerate(zip(old, new), 1):
                if before["case"] != after["case"]:
                    raise ValueError(f"{name} row {index}: cases differ between models")
                for row in (before, after):
                    if any(type(row[key]) is not bool for key in ("correct_tool", "correct_args")):
                        raise ValueError(f"{name} row {index}: scores must be booleans")
                    if row["correct_args"] and not row["correct_tool"]:
                        raise ValueError(f"{name} row {index}: inconsistent scores")
            pairs[name] = (old, new)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        parser.error(str(exc))

    print("| Set | Model | Tool | Tool + args |\n|---|---|---:|---:|")
    recommend = True
    for name, (old, new) in pairs.items():
        scores = []
        for model, rows in (("v2", old), ("v3", new)):
            tool = sum(row["correct_tool"] for row in rows)
            exact = sum(row["correct_args"] for row in rows)
            scores.append((tool, exact))
            n = len(rows)
            print(f"| {name} | {model} | {tool}/{n} ({tool/n:.1%}) | {exact}/{n} ({exact/n:.1%}) |")
        recommend &= scores[1][1] > scores[0][1] and scores[1][0] >= scores[0][0]
    regressions = [before["case"] for before, after in zip(*pairs["real"])
                   if any(before[key] and not after[key] for key in ("correct_tool", "correct_args"))]
    print(f"\nReal-case regressions: {len(regressions)}")
    for case in regressions:
        print("- " + json.dumps(case, ensure_ascii=False))
    print("\nRecommendation: " + ("v3" if recommend and not regressions else "retain v2"))


if __name__ == "__main__":
    main()
