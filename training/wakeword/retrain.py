"""Prepare reviewed wake feedback; augmentation and training require --train."""
import argparse
from collections import defaultdict
from datetime import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
WW = ROOT / "models/wakeword"
CLIP = re.compile(r"^(\d{8}_\d{6})_s\d+_(yes|no|missed|near|none)\.wav$")


def load_label_fixes(path):
    data = json.loads(Path(path).read_text())
    fixes = dict(data.get("clips", {}))
    for event in data.get("events", []):
        prefix, verdict = event["clip_prefix"], event["verdict"]
        if not re.fullmatch(r"\d{8}_\d{6}", prefix):
            raise ValueError(f"Invalid event clip_prefix: {prefix}")
        if prefix in fixes and fixes[prefix] != verdict:
            raise ValueError(f"Conflicting label fixes: {prefix}")
        fixes[prefix] = verdict
    if any(verdict not in ("yes", "no") for verdict in fixes.values()):
        raise ValueError("Label fixes must use yes or no")
    return fixes


def classify_clip(path, fixes):
    match = CLIP.fullmatch(Path(path).name)
    if not match:
        return None
    verdict = fixes.get(Path(path).name, fixes.get(match[1], match[2]))
    return {"yes": "positive", "missed": "positive", "no": "negative"}.get(verdict)


def build_dataset(feedback_dir, output_dir, fixes):
    feedback_dir, output_dir = Path(feedback_dir), Path(output_dir)
    for folder in ("pos", "neg"):
        (output_dir / folder).mkdir(parents=True, exist_ok=False)
    groups = defaultdict(list)
    counts = dict(positive=0, negative=0, skipped=0, conflicts=0)
    manifest = []
    for clip in sorted(feedback_dir.rglob("*.wav")):
        if "_pull" in clip.relative_to(feedback_dir).parts:
            continue
        if not CLIP.fullmatch(clip.name):
            counts["skipped"] += 1
            continue
        groups[clip.stem.rsplit("_", 1)[0]].append(clip)
    for identity, clips in sorted(groups.items()):
        # Pulls retain older filename verdicts; a reviewed answer beats near/none.
        labeled = [(clip, classify_clip(clip, fixes)) for clip in clips]
        overrides = [(clip, label) for clip, label in labeled if clip.name in fixes]
        if overrides:
            labeled = overrides
        labels = {label for _, label in labeled if label is not None}
        if len(labels) != 1:
            reason = "conflicting verdicts" if labels else "unreviewed"
            counts["conflicts" if labels else "skipped"] += 1
            manifest.append({"identity": identity, "label": None, "reason": reason})
            continue
        label = labels.pop()
        source = next(clip for clip, value in labeled if value == label)
        target = output_dir / ("pos" if label == "positive" else "neg") / (identity + ".wav")
        shutil.copy2(source, target)
        counts[label] += 1
        manifest.append({"identity": identity, "source": str(source.resolve()),
                         "label": label, "destination": str(target.resolve())})
    (output_dir / "manifest.json").write_text(json.dumps({"counts": counts, "clips": manifest}, indent=2) + "\n")
    return counts


def pull_feedback():
    if not shutil.which("adb"):
        print("Pull skipped: adb is unavailable.")
        return
    try:
        state = subprocess.run(["adb", "get-state"], capture_output=True, text=True, timeout=10)
        if state.returncode or state.stdout.strip() != "device":
            print("Pull skipped: no selected, authorized phone (set ANDROID_SERIAL if needed).")
            return
        subprocess.run(["bash", str(HERE / "pull_feedback.sh")], cwd=ROOT, check=True, timeout=120)
    except subprocess.TimeoutExpired:
        print("Pull skipped: adb timed out; using cached feedback.")
    except subprocess.CalledProcessError as error:
        raise RuntimeError("Phone connected but feedback pull failed; retry or use --skip-pull.") from error


def train(dataset, python):
    # Keep the original model, features and train/test split intact.
    baseline = WW / "out/hey_jimi"
    folders = ("positive_train", "negative_train", "positive_test", "negative_test")
    required = [WW / "oww_train.py", WW / "data/acav_part.npy",
                WW / "data/validation_set_features.npy"]
    missing = [str(path) for path in required if not path.is_file()]
    missing += [str(baseline / folder) for folder in folders
                if not any((baseline / folder).glob("*.wav"))]
    if missing:
        raise RuntimeError("Training prerequisites missing (see README): " + ", ".join(missing))
    features = dataset / "out/hey_jimi"
    for folder in folders:
        target = features / folder
        target.mkdir(parents=True)
        for source in (baseline / folder).glob("*.wav"):
            shutil.copy2(source, target / source.name)
    for label, folder, copies in (("pos", "positive_train", 40), ("neg", "negative_train", 5)):
        for source in (dataset / label).glob("*.wav"):
            for copy in range(copies):
                shutil.copy2(source, features / folder / f"feedback_{source.stem}_{copy}.wav")
    config = dataset / "hey_jimi.yml"
    original = (HERE / "hey_jimi.yml").read_text()
    config.write_text(re.sub(r'^output_dir:.*$', 'output_dir: ' + json.dumps(str(dataset / "out")),
                             original, flags=re.MULTILINE))
    env = dict(os.environ, OMP_NUM_THREADS="2", MKL_NUM_THREADS="2", OPENBLAS_NUM_THREADS="2")
    subprocess.run([python, str(HERE / "run_oww_train.py"), str(WW / "oww_train.py"),
                    "--training_config", str(config), "--augment_clips"], cwd=ROOT, env=env, check=True)
    subprocess.run([python, str(HERE / "train_fast.py"), "--features-dir", str(features),
                    "--neg_weight", "5000", "--layer", "64", "--steps", "20000",
                    "--acav_rows", "800000", "--out", str(dataset / "hey_jimi_candidate.onnx")],
                   cwd=ROOT, env=env, check=True)
    print("Candidate saved; compare old/new on the same held-out clips before any manual swap.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-pull", action="store_true", help="Use local feedback without contacting adb")
    parser.add_argument("--feedback-dir", type=Path, default=WW / "feedback")
    parser.add_argument("--label-fixes", type=Path, default=HERE / "label_fixes.json")
    parser.add_argument("--train", action="store_true", help="Explicitly enable augmentation and D-024 training")
    parser.add_argument("--python", default=os.environ.get("PY", str(ROOT / ".venv-ww/bin/python")
                        if (ROOT / ".venv-ww/bin/python").exists() else sys.executable))
    args = parser.parse_args()
    try:
        fixes = load_label_fixes(args.label_fixes)
        if not args.skip_pull:
            if args.feedback_dir.resolve() != (WW / "feedback").resolve():
                parser.error("Custom --feedback-dir requires --skip-pull; pull_feedback.sh uses the default directory")
            pull_feedback()
        runs = WW / "retrain"
        runs.mkdir(parents=True, exist_ok=True)
        dataset = Path(tempfile.mkdtemp(prefix=datetime.now().strftime("%Y%m%d_%H%M%S_"), dir=runs))
        counts = build_dataset(args.feedback_dir, dataset, fixes)
        print("Feedback: " + ", ".join(f"{key}={value}" for key, value in counts.items()))
        print(f"Dataset: {dataset}")
        if args.train:
            if not counts["positive"] and not counts["negative"]:
                raise RuntimeError("No labeled feedback; training was not started.")
            train(dataset, args.python)
        else:
            print("Dry run complete: training disabled (requires --train).")
        return 0
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
