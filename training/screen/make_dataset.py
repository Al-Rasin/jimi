#!/usr/bin/env python3
"""Build training/data/screen/{train,valid}.jsonl in chat format from labelled.jsonl (+ optional replay).

Replay: OfficerChul/Android-Control-84k (pinned revision) has screenshots + x,y answers and NO accessibility
text list (checked 2026-10-25: fields `messages` [system,user "<image>...",assistant] and `images` paths).
So it cannot be used in our text format; --replay only works if the rows contain "Current Android UI",
otherwise it prints a note and adds nothing. --replay-file takes your own jsonl in our chat format instead.
The 15 eval screens (by name and by element-list hash) are excluded. Split is by screen.
"""
import argparse
import hashlib
import json
import pathlib
import random
import sys
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from collect import screen_hash  # noqa: E402
from make_goals import HELD_OUT  # noqa: E402
from prompt import SYS, elements, messages, parse_elements  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA = ROOT / "training/data/screen"
DATASET = "OfficerChul/Android-Control-84k"
REVISION = "0248027f747c9d57bd09c14e8f044f9a8103dddd"
VIEWER = f"https://datasets-server.huggingface.co/first-rows?dataset={DATASET}&config=default&split=train"


def eval_hashes(screens_dir):
    hs = set()
    for name in HELD_OUT:
        p = screens_dir / f"{name}.xml"
        if p.is_file():
            hs.add(screen_hash(parse_elements(p)[3]))
    return hs


def replay_rows(n):
    """Rows from the HF dataset that already use our text format (none today). Returns [] and says why."""
    if n <= 0:
        return []
    try:
        rows = json.load(urllib.request.urlopen(VIEWER, timeout=60))["rows"]
    except Exception as e:
        print("replay: cannot read dataset viewer:", e)
        return []
    ok = [r["row"] for r in rows if "Current Android UI" in json.dumps(r["row"]["messages"])]
    if not ok:
        print(f"replay: {DATASET}@{REVISION[:7]} has screenshots only (no a11y text list); skipped.")
        return []
    print("replay: text rows found, but full download is not implemented; skipped.")
    return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--labelled", type=pathlib.Path, default=DATA / "labelled.jsonl")
    ap.add_argument("--raw", type=pathlib.Path, default=DATA / "raw")
    ap.add_argument("--screens", type=pathlib.Path, default=pathlib.Path("/tmp/guitest"), help="eval screens to hold out")
    ap.add_argument("--out", type=pathlib.Path, default=DATA)
    ap.add_argument("--replay", type=int, default=2000)
    ap.add_argument("--replay-file", type=pathlib.Path, help="extra rows already in {'messages': [...]} format")
    ap.add_argument("--valid-frac", type=float, default=0.1)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()

    held = eval_hashes(a.screens)
    by_screen = {}
    skipped = 0
    for r in map(json.loads, filter(str.strip, open(a.labelled))):
        stem = pathlib.Path(r["screen"]).stem
        xml = a.raw / r["screen"]
        if stem in HELD_OUT or not xml.is_file() or screen_hash(parse_elements(xml)[3]) in held:
            skipped += 1
            continue
        row = {"messages": messages(r["goal"], elements(xml)) + [{"role": "assistant", "content": r["answer"]}]}
        by_screen.setdefault(r["screen"], []).append(row)
    print(f"{sum(map(len, by_screen.values()))} rows from {len(by_screen)} screens ({skipped} held-out/missing skipped)")

    screens = sorted(by_screen)
    random.Random(a.seed).shuffle(screens)
    n_valid = max(1, round(len(screens) * a.valid_frac)) if len(screens) > 1 else 0
    valid_screens = set(screens[:n_valid])
    train = [r for s in screens if s not in valid_screens for r in by_screen[s]]
    valid = [r for s in screens if s in valid_screens for r in by_screen[s]]
    extra = replay_rows(a.replay)
    if a.replay_file:
        extra += [json.loads(l) for l in open(a.replay_file) if l.strip()]
    train += extra
    random.Random(a.seed).shuffle(train)
    a.out.mkdir(parents=True, exist_ok=True)
    for name, rows in (("train", train), ("valid", valid)):
        with open(a.out / f"{name}.jsonl", "w") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"train {len(train)}  valid {len(valid)} -> {a.out}")
    if not valid:
        print("WARNING: no valid rows (need at least 2 screens)")


if __name__ == "__main__":
    main()
