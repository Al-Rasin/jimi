#!/usr/bin/env python3
"""Build the PRIVATE Kaggle upload dir for teacher labelling (ROADMAP #66 round 3).

Only element TEXT goes up (never xml/png). Output: training/data/screen/kaggle-upload-<run_id>/
    screens.jsonl  {screen, elements, template_goals}
    prompt.py, privacy.py  (the kernel imports them; private_words.txt is NOT copied)
    run_mode.json, dataset-metadata.json
"""
import argparse
import json
import pathlib
import random
import shutil
import sys

SCREEN = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCREEN))
from make_goals import DATA, HELD_OUT, template_goals  # noqa: E402
from privacy import looks_private  # noqa: E402
from prompt import elements, parse_elements  # noqa: E402

DATASET_SLUG = "jarvis-screen-teachers"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--owner", required=True, help="Kaggle username")
    ap.add_argument("--mode", choices=["smoke", "real"], required=True)
    ap.add_argument("--run-id")
    ap.add_argument("--raw", type=pathlib.Path, default=DATA / "raw")
    ap.add_argument("--max-templates", type=int, default=0,
                    help="keep at most N template goals per screen (0 = all); big screens flooded round 3")
    a = ap.parse_args()
    run_id = a.run_id or a.mode
    out = DATA / f"kaggle-upload-{run_id}"
    if out.exists():
        sys.exit(f"{out} exists; pick a new --run-id (nothing is overwritten)")
    rows = []
    for xml in sorted(a.raw.glob("*.xml")):
        if xml.stem in HELD_OUT or xml.name.startswith("."):
            continue
        goals = template_goals(xml)
        if a.max_templates and len(goals) > a.max_templates:
            goals = random.Random(xml.name).sample(goals, a.max_templates)
        rows.append({"screen": xml.name, "elements": elements(xml), "template_goals": goals})
    if a.mode == "smoke":
        rows = rows[:5]
    # privacy re-check on every label that will leave the machine
    for r in rows:
        bad = [lab for _, lab, _, _, _ in parse_elements(a.raw / r["screen"])[3] if looks_private(lab)]
        bad += [g for g in r["template_goals"] if looks_private(g)]
        if bad:
            sys.exit(f"PRIVATE text in {r['screen']}: {bad[:3]} -- aborting, nothing written")
    out.mkdir(parents=True)
    with open(out / "screens.jsonl", "w") as f:
        f.writelines(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
    shutil.copyfile(SCREEN / "prompt.py", out / "prompt.py")
    shutil.copyfile(SCREEN / "privacy.py", out / "privacy.py")
    (out / "run_mode.json").write_text(json.dumps({"mode": a.mode, "screens": len(rows)}))
    (out / "dataset-metadata.json").write_text(json.dumps(
        {"title": "Jarvis screen teachers", "id": f"{a.owner}/{DATASET_SLUG}",
         "licenses": [{"name": "CC0-1.0"}], "isPrivate": True}, indent=2))
    print(f"{len(rows)} screens, {sum(len(r['template_goals']) for r in rows)} template goals -> {out}")


if __name__ == "__main__":
    main()
