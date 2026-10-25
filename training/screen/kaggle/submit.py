#!/usr/bin/env python3
"""Push the upload dir as a PRIVATE dataset and run the PRIVATE kernel; poll; download.

    submit.py --owner USER --mode smoke|real [--run-id ID]      # upload + push kernel (+ --wait to poll and fetch)
    submit.py --owner USER --run-id ID --status
    submit.py --owner USER --run-id ID --fetch                  # download to training/data/screen/kaggle-out-<id>/
Never passes --public. Credentials are read by the kaggle CLI from ~/.kaggle itself.
"""
import argparse
import json
import pathlib
import shutil
import subprocess
import sys
import time

SCREEN = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCREEN))
from make_goals import DATA  # noqa: E402
from privacy import looks_private  # noqa: E402

CLI = "/private/tmp/jarvis-kaggle-venv/bin/kaggle"
HERE = pathlib.Path(__file__).resolve().parent


def run(*args, check=True):
    r = subprocess.run([CLI, *args], text=True, capture_output=True)
    if check and r.returncode:
        sys.exit(f"kaggle {' '.join(args[:2])} failed: {(r.stdout + r.stderr).strip()[-600:]}")
    return r


def kernel_id(owner, run_id):
    return f"{owner}/jarvis-screen-label-{run_id}"


def wait_dataset(ref):
    for _ in range(60):
        r = run("datasets", "status", ref, check=False)
        low = (r.stdout + r.stderr).lower()
        if "ready" in low:
            return
        if "error" in low or "fail" in low:
            raise RuntimeError(low)
        time.sleep(20)
    raise TimeoutError("dataset not ready")


def push(a):
    run_id = a.run_id or a.mode
    upload = DATA / f"kaggle-upload-{run_id}"
    meta = json.loads((upload / "dataset-metadata.json").read_text())
    if meta.get("isPrivate") is not True or meta["id"].split("/")[0] != a.owner:
        sys.exit("dataset metadata must be private and owned by --owner")
    if json.loads((upload / "run_mode.json").read_text())["mode"] != a.mode:
        sys.exit("prepared mode differs from --mode")
    exists = run("datasets", "status", meta["id"], check=False).returncode == 0
    if exists:   # new version replaces the dataset content (smoke -> real)
        print(run("datasets", "version", "-p", str(upload), "-m", run_id).stdout)
    else:        # CLI creates datasets PRIVATE by default
        print(run("datasets", "create", "-p", str(upload)).stdout)
    wait_dataset(meta["id"])
    kdir = DATA / f"kaggle-kernel-{run_id}"
    kdir.mkdir(exist_ok=False)
    cfg = json.loads((HERE / "kernel-metadata.json").read_text())
    cfg.update(id=kernel_id(a.owner, run_id), title=f"Jarvis screen label {run_id}", is_private=True,
               dataset_sources=[meta["id"]])
    (kdir / "kernel-metadata.json").write_text(json.dumps(cfg, indent=2))
    shutil.copyfile(HERE / "kernel.py", kdir / "kernel.py")
    print(run("kernels", "push", "-p", str(kdir), "--accelerator", "NvidiaTeslaT4").stdout)


def status(a):
    r = run("kernels", "status", kernel_id(a.owner, a.run_id), check=False)
    s = (r.stdout + r.stderr).strip()
    print(s, flush=True)
    return s.lower()


def fetch(a):
    out = DATA / f"kaggle-out-{a.run_id}"
    out.mkdir(parents=True, exist_ok=True)
    print(run("kernels", "output", kernel_id(a.owner, a.run_id), "-p", str(out)).stdout)
    # drop any goal that trips the local privacy filter (it also knows private_words.txt); merge ignores their labels
    gp = out / "goals.jsonl"
    if gp.is_file():
        rows = [json.loads(l) for l in open(gp) if l.strip()]
        keep = [r for r in rows if not looks_private(r["goal"])]
        if len(keep) != len(rows):
            gp.rename(out / "goals.unfiltered.jsonl")
            gp.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in keep))
        print(f"goals kept {len(keep)}/{len(rows)} after local privacy check")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--owner", required=True)
    ap.add_argument("--mode", choices=["smoke", "real"])
    ap.add_argument("--run-id")
    ap.add_argument("--wait", action="store_true", help="after push, poll every 3 min and fetch when complete")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--fetch", action="store_true")
    a = ap.parse_args()
    if a.status or a.fetch:
        if not a.run_id:
            ap.error("--run-id needed")
        status(a) if a.status else fetch(a)
        return
    if not a.mode:
        ap.error("--mode needed")
    a.run_id = a.run_id or a.mode
    push(a)
    if a.wait:
        deadline = time.monotonic() + 12.5 * 3600
        while time.monotonic() < deadline:
            s = status(a)
            if "complete" in s:
                fetch(a)
                return
            if any(x in s for x in ("error", "cancel", "fail")):
                raise SystemExit("kernel ended badly; try --fetch for partial output")
            time.sleep(180)
        raise SystemExit("poll timeout; kernel may still run")


if __name__ == "__main__":
    main()
