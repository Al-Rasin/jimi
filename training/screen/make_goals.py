#!/usr/bin/env python3
"""Build candidate goals per raw screen -> training/data/screen/goals.jsonl rows {screen, goal}.

Templates only by default. --llm-goals asks a teacher (llama-server on --port) for 3 natural goals per screen.
The 15 eval screens are never in raw/, but any file named like an eval screen is skipped too.
"""
import argparse
import random
import json
import pathlib
import sys
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from privacy import looks_private  # noqa: E402
from prompt import elements, parse_elements  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA = ROOT / "training/data/screen"
HELD_OUT = {"settings", "display", "home"}
# "Open Back" taught the model to answer target 0 (the Back button) on unknown screens; "Go back" covers it.
NAV_LABELS = {"back", "navigate up", "more options", "up"}


def template_goals(path):
    _, _, _, els = parse_elements(path)
    goals = []
    for _, label, _, flags, _ in els:
        if "click" in flags and label and label.strip().lower() not in NAV_LABELS and not looks_private(label):
            goals += [f"Open {label}", f"Go to {label}"]
    goals.append("Go back")
    if any("scroll" in f for _, _, _, f, _ in els):
        goals.append("Scroll down")
    return list(dict.fromkeys(goals))


def llm_goals(path, port):
    prompt = ("Below is the UI of an Android screen. Write 3 short, natural things a user might ask a voice assistant "
              "to do on this screen. One per line, no numbering, no extra text.\n\n" + elements(path))
    body = json.dumps({"messages": [{"role": "user", "content": prompt}], "temperature": 0.7, "max_tokens": 3000,
                       "chat_template_kwargs": {"enable_thinking": False}}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", body, {"Content-Type": "application/json"})
    text = json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"].get("content") or ""
    return [l.strip(" -*\t") for l in text.splitlines() if 3 < len(l.strip()) < 80 and not looks_private(l)][:3]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", type=pathlib.Path, default=DATA / "raw")
    ap.add_argument("--out", type=pathlib.Path, default=DATA / "goals.jsonl")
    ap.add_argument("--llm-goals", action="store_true")
    ap.add_argument("--port", type=int, default=8081, help="teacher server for --llm-goals")
    a = ap.parse_args()
    a.out.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    for xml in sorted(a.raw.glob("*.xml")):
        if xml.stem in HELD_OUT or xml.name.startswith("."):
            continue
        goals = template_goals(xml)
        if a.llm_goals:
            try:
                goals += llm_goals(xml, a.port)
            except Exception as e:
                print("llm goals failed for", xml.name, e)
        rows += [{"screen": xml.name, "goal": g} for g in dict.fromkeys(goals)]
    # shuffled (fixed seed) so `label.py --limit N` covers many screens, not the first few
    random.Random(0).shuffle(rows)
    with open(a.out, "w") as f:
        f.writelines(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
    n = len(rows)
    print("wrote", n, "goals to", a.out)


if __name__ == "__main__":
    main()
