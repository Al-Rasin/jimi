"""Generate small fake notification rows; never reads a phone."""

import argparse
import json
from pathlib import Path
import random


def generate(count=80, seed=13):
    if count < 4:
        raise ValueError("count must be at least four")
    rng = random.Random(seed)
    for index in range(count):
        keep = index % 2 == 0
        yield {"id": index + 1, "timeMs": 1_700_000_000_000 + index * 3_600_000,
               "pkg": "example.messages" if keep else "example.shop",
               "title": rng.choice(["Team message", "Meeting reminder"]) if keep else "Flash sale",
               "text": "Please call before the meeting" if keep else "Discount coupon save today",
               "isConversation": keep, "isGroupConversation": keep and index % 4 == 0,
               "status": "kept" if keep else "dismissed", "label": "important" if keep else "noise"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "output/synthetic.jsonl")
    parser.add_argument("--count", type=int, default=80)
    args = parser.parse_args()
    rows = list(generate(args.count))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
    print(f"Wrote {len(rows)} synthetic rows to {args.output}")


if __name__ == "__main__":
    main()
