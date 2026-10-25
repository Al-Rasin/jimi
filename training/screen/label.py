#!/usr/bin/env python3
"""Teacher labelling through a llama-server OpenAI API (ROADMAP #66).

Run one teacher at a time (8 GB Mac):
    label.py --teacher a --port 8081      # Qwen3-4B, thinks -> slow
    label.py --teacher b --port 8082      # Qwen3-1.7B
    label.py --merge                      # keep rows where both teachers agree -> labelled.jsonl
Resume-safe: answers are appended to labels_<teacher>.jsonl and finished (screen, goal) keys are skipped.
"""
import argparse
import json
import pathlib
import sys
import time
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from prompt import action_key, compact, elements, messages, parse_action  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA = ROOT / "training/data/screen"
TEACHERS = {"a": {"name": "qwen3-4b", "think": True, "max_tokens": 3000},
            "b": {"name": "qwen3-1.7b", "think": False, "max_tokens": 512}}


def ask(port, msgs, think, max_tokens):
    body = {"messages": msgs, "temperature": 0, "max_tokens": max_tokens}
    if not think:
        body["chat_template_kwargs"] = {"enable_thinking": False}
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    m = json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"]
    # thinking models may leave `content` empty when the budget ends inside the reasoning
    return (m.get("content") or "").strip() or (m.get("reasoning_content") or "")


def done_keys(path):
    if not path.is_file():
        return set()
    return {(r["screen"], r["goal"]) for r in map(json.loads, filter(str.strip, open(path)))}


def key_of(r):
    return r["screen"], r["goal"]


def label(a):
    t = TEACHERS[a.teacher]
    out = a.labels_dir / f"labels_{t['name']}.jsonl"
    goals = [json.loads(l) for l in open(a.goals) if l.strip()]
    done = done_keys(out)
    todo = [g for g in goals if key_of(g) not in done]
    if a.limit:
        todo = todo[: a.limit]
    print(f"{t['name']}: {len(done)} done, {len(todo)} to do")
    t0 = time.time()
    with open(out, "a") as f:
        for i, g in enumerate(todo, 1):
            xml = a.raw / g["screen"]
            try:
                text = ask(a.port, messages(g["goal"], elements(xml)), t["think"], t["max_tokens"])
            except Exception as e:
                print("  error", g, e)
                continue
            act = parse_action(text)
            f.write(json.dumps({**g, "raw": text[-300:], "action": act}, ensure_ascii=False) + "\n")
            f.flush()
            if i % 10 == 0 or i == len(todo):
                print(f"  {i}/{len(todo)}  {(time.time() - t0) / i:.1f}s/row", flush=True)


def merge(a):
    rows = {}
    for t in TEACHERS.values():
        p = a.labels_dir / f"labels_{t['name']}.jsonl"
        rows[t["name"]] = {key_of(r): r for r in map(json.loads, filter(str.strip, open(p)))} if p.is_file() else {}
    na, nb = (rows[t["name"]] for t in TEACHERS.values())
    wanted = {key_of(json.loads(l)) for l in open(a.goals) if l.strip()}   # old labels of dropped goals stay out
    kept = total = 0
    with open(a.out, "w") as f:
        for k, ra in na.items():
            rb = nb.get(k)
            if k not in wanted or not rb or not ra["action"] or not rb["action"]:
                continue
            total += 1
            ka, kb = action_key(ra["action"]), action_key(rb["action"])
            if ka is None or ka != kb:
                continue
            if ka[0] in ("click", "long_press") and ka[1] >= 32:
                continue
            f.write(json.dumps({"screen": k[0], "goal": k[1], "answer": compact(ra["action"])}, ensure_ascii=False) + "\n")
            kept += 1
    print(f"both answered {total}, agree {kept} -> {a.out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", choices=sorted(TEACHERS))
    ap.add_argument("--merge", action="store_true")
    ap.add_argument("--port", type=int, default=8081)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--raw", type=pathlib.Path, default=DATA / "raw")
    ap.add_argument("--labels-dir", type=pathlib.Path, default=DATA, help="where labels_<teacher>.jsonl live")
    ap.add_argument("--goals", type=pathlib.Path, default=DATA / "goals.jsonl")
    ap.add_argument("--out", type=pathlib.Path, default=DATA / "labelled.jsonl")
    a = ap.parse_args()
    if a.merge:
        merge(a)
    elif a.teacher:
        label(a)
    else:
        ap.error("give --teacher a|b or --merge")


if __name__ == "__main__":
    main()
