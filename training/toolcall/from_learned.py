"""Training rows from what the phone learned (ROADMAP #41).

Sources (any mix):
  --db      jarvis.db snapshot (training/notifications/export_db.py pulls it): app map, recipes, user fixes
  --appmap  jarvis_appmap_*.json from Settings → export (app map only)

Rows go to training/data/toolcall/learned.jsonl as {"text", "tool", "args"}; make_dataset.py --learned adds them.
The app map does the screen work, so the model only learns to pick the task: "open X in Y" → app_go(app, target),
recipe phrases → run_recipe(name, app), and a fixed mishear → the tool the fixed text ran.
No personal text: labels that look private (numbers, emails, private_words.txt) are dropped, and only
fixes that ran a tool without error are kept.
"""
import argparse
import json
import pathlib
import random
import re
import sqlite3
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "training" / "screen"))
from privacy import looks_private  # noqa: E402

OUT = ROOT / "training" / "data" / "toolcall" / "learned.jsonl"

NAV_ROLES = {"tab", "drawer", "menu", "button"}
GO_TEMPLATES = ["open {target} in {app}", "go to {target} in {app}", "show {target} on {app}",
                "{app} {target}", "take me to {app} {target}", "open {app} {target}"]
RECIPE_PREFIX = ["", "", "please ", "can you "]
# Sample values for recipe holes ({contact}, {text}, ...). Made-up words only, never real user data.
SAMPLES = {
    "contact": ["mom", "rahim", "office", "sara"],
    "name": ["mom", "rahim", "sara"],
    "number": ["mom", "office"],
    "text": ["on my way", "call me later", "good night"],
    "message": ["on my way", "see you soon"],
    "query": ["coffee", "weather", "news"],
    "song": ["lofi beats", "rain sounds"],
    "place": ["dhaka", "home", "airport"],
    "amount": ["two", "five"],
}
DEFAULT_SAMPLES = ["something", "that"]
HOLE_RE = re.compile(r"\{(\w+)\}")
MAX_LABEL = 30


def clean_label(label):
    """A usable spoken target, or None when empty, too long, private or not words."""
    if not label:
        return None
    t = re.sub(r"\s+", " ", label).strip().strip(".:").lower()
    if not t or len(t) > MAX_LABEL or looks_private(t):
        return None
    if sum(c.isalpha() for c in t) < max(2, len(t) // 2):
        return None
    return t


def app_go_rows(apps, screens, elements, rng, per_target=2):
    """apps: pkg → label; screens: [{id, pkg, name}]; elements: [{screen, role, label, risk}]."""
    pkg_of = {s["id"]: s["pkg"] for s in screens}
    targets = {}
    for s in screens:
        t = clean_label(s.get("name"))
        if t:
            targets.setdefault(s["pkg"], set()).add(t)
    for e in elements:
        if e.get("risk", "low") != "low" or e.get("role") not in NAV_ROLES:
            continue
        pkg, t = pkg_of.get(e.get("screen")), clean_label(e.get("label"))
        if pkg and t:
            targets.setdefault(pkg, set()).add(t)
    rows = []
    for pkg in sorted(targets):
        app = clean_label(apps.get(pkg))
        if not app:
            continue
        for t in sorted(targets[pkg]):
            if t == app:
                continue
            for tpl in rng.sample(GO_TEMPLATES, min(per_target, len(GO_TEMPLATES))):
                rows.append({"text": tpl.format(app=app, target=t), "tool": "app_go", "args": {"app": app, "target": t}})
    return rows


def fill(say, rng):
    return HOLE_RE.sub(lambda m: rng.choice(SAMPLES.get(m.group(1).lower(), DEFAULT_SAMPLES)), say)


def recipe_rows(apps, recipes, rng, per_line=2):
    """recipes: [{pkg, name, say, risky}]. Risky recipes are kept: the app still asks before running them."""
    rows = []
    for rc in recipes:
        app = clean_label(apps.get(rc.get("pkg"))) or None
        name = (rc.get("name") or "").strip()
        if not name or looks_private(name):
            continue
        args = {"name": name}
        if app:
            args["app"] = app
        if rc.get("risky"):
            args["risky"] = "true"
        for line in (rc.get("say") or "").splitlines():
            line = re.sub(r"\s+", " ", line).strip().lower()
            if not line or looks_private(HOLE_RE.sub("", line)):
                continue
            for _ in range(per_line if HOLE_RE.search(line) else 1):
                text = rng.choice(RECIPE_PREFIX) + fill(line, rng)
                rows.append({"text": text, "tool": "run_recipe", "args": dict(args)})
    return rows


def fix_rows(turns, calls):
    """turns: [{heard, textOk, correction}]; calls: [{heard, tool, argsJson, source, ok}].
    A ✗-fixed turn whose fixed text then ran a tool OK → (what was heard) → that tool."""
    ran = {}
    for c in calls:
        if c.get("source") == "fix" and c.get("ok"):
            ran[c["heard"].strip().lower()] = c
    rows = []
    for t in turns:
        fixed = (t.get("correction") or "").strip().lower()
        heard = (t.get("heard") or "").strip().lower()
        c = ran.get(fixed)
        if t.get("textOk") not in (0, False) or not c or not heard or heard == fixed or looks_private(heard):
            continue
        try:
            args = json.loads(c.get("argsJson") or "{}")
        except ValueError:
            continue
        if any(looks_private(str(v)) for v in args.values()):
            continue
        rows.append({"text": heard, "tool": c["tool"], "args": {k: str(v) for k, v in args.items()}})
    return rows


def _table(con, sql):
    try:
        con.row_factory = sqlite3.Row
        return [dict(r) for r in con.execute(sql)]
    except sqlite3.OperationalError:
        return []   # older db without that table


def read_db(path):
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return {
            "app_info": _table(con, "SELECT pkg, label FROM app_info"),
            "app_screen": _table(con, "SELECT id, pkg, name FROM app_screen"),
            "app_element": _table(con, "SELECT screen, role, label, risk FROM app_element"),
            "recipes": _table(con, "SELECT pkg, name, say, risky FROM recipes"),
            "turns": _table(con, "SELECT heard, textOk, correction FROM conversation_turn WHERE correction IS NOT NULL"),
            "calls": _table(con, "SELECT heard, tool, argsJson, source, ok FROM tool_call_log WHERE source = 'fix'"),
        }
    finally:
        con.close()


def build(sources, seed=1):
    rng = random.Random(seed)
    merged = {k: [] for k in ("app_info", "app_screen", "app_element", "recipes", "turns", "calls")}
    for src in sources:
        for k in merged:
            merged[k] += src.get(k, [])
    apps = {a["pkg"]: a.get("label") for a in merged["app_info"]}
    rows = (app_go_rows(apps, merged["app_screen"], merged["app_element"], rng)
            + recipe_rows(apps, merged["recipes"], rng)
            + fix_rows(merged["turns"], merged["calls"]))
    out, seen = [], set()
    for row in rows:
        if row["text"] not in seen:
            seen.add(row["text"])
            out.append(row)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=pathlib.Path, action="append", default=[])
    ap.add_argument("--appmap", type=pathlib.Path, action="append", default=[])
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    if not a.db and not a.appmap:
        ap.error("give --db and/or --appmap")
    sources = [read_db(p) for p in a.db] + [json.loads(p.read_text()) for p in a.appmap]
    rows = build(sources, a.seed)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    with open(a.out, "w") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    by_tool = {}
    for row in rows:
        by_tool[row["tool"]] = by_tool.get(row["tool"], 0) + 1
    print(f"{len(rows)} rows {by_tool} → {a.out}")


if __name__ == "__main__":
    main()
