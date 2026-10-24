#!/usr/bin/env python3
"""Run the 15 held-out cases against a llama-server port and print OK/BAD + RESULT.

    python3 training/screen/eval.py --port 8083 --name new --screens /tmp/guitest
"""
import argparse
import json
import pathlib
import sys
import time
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from cases import CASES  # noqa: E402
from prompt import elements, messages, score  # noqa: E402


def ask(port, msgs):
    body = json.dumps({"messages": msgs, "temperature": 0, "max_tokens": 256,
                       "chat_template_kwargs": {"enable_thinking": False}}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", body, {"Content-Type": "application/json"})
    m = json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"]
    return (m.get("content") or "") or ("REASON:" + (m.get("reasoning_content") or ""))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8083)
    ap.add_argument("--name", default="model")
    ap.add_argument("--screens", type=pathlib.Path, default=pathlib.Path("/tmp/guitest"))
    a = ap.parse_args()
    good = 0
    t0 = time.time()
    for screen, goal, ok in CASES:
        xml = a.screens / f"{screen}.xml"
        out = ask(a.port, messages(goal, elements(xml)))
        s = score(str(xml), out, ok)
        good += s
        print(f'{"OK " if s else "BAD"} {screen:8} {goal:32} {out.strip()[:90]!r}', flush=True)
    print(f"RESULT {a.name}: {good}/{len(CASES)}  {(time.time() - t0) / len(CASES):.1f}s/case", flush=True)


if __name__ == "__main__":
    main()
