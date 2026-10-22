#!/usr/bin/env python3
"""Acquire attributed Mobile Actions rows and apply the conservative converter."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.request

import convert_public
from r1_contract import load_contract

REVISION = "e920309bc2acbc2e99a5e3201cf37df2b9fd9151"
SOURCE_SHA256 = "91d251ee958cfd295af6c4504c236a3a1ad19517de240c3bc680bacfcbf7e7d9"
REPOSITORY = "https://huggingface.co/datasets/google/mobile-actions"
SOURCE_DIR = Path("/Users/alrasin/Development/Personal/jarvis/training/data/toolcall_r1_sources")


def review_reason(text):
    lowered = text.lower()
    if not re.search(r"\b(?:flashlight|torch)\b", lowered):
        return "review_ambiguous_light_target"
    if "how do i" in lowered or "how do that" in lowered:
        return "review_how_to_question"
    return None


def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()


def acquire(source_dir=SOURCE_DIR):
    """Cache the pinned release and verify its publisher LFS SHA256."""
    source_dir.mkdir(parents=True, exist_ok=True)
    original = source_dir / "mobile-actions-dataset.jsonl"
    if not original.exists():
        subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error",
            "--max-time", "120", f"{REPOSITORY}/resolve/{REVISION}/dataset.jsonl?download=1",
            "--output", str(original)], check=True)
    if hashlib.sha256(original.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Public source SHA256 mismatch; refusing unverified/partial source")
    card = source_dir / "mobile-actions-README.md"
    if not card.exists():
        card.write_bytes(fetch(f"{REPOSITORY}/raw/{REVISION}/README.md"))
    if "license: cc-by-4.0" not in card.read_text():
        raise ValueError("Pinned source card does not declare the expected license")
    return [original]


def candidates(prefix, held_out=(), source_dir=SOURCE_DIR):
    schemas = convert_public.schema_from_prefix(prefix)
    paths = acquire(source_dir)
    accepted, counts, seen = [], Counter(), set()
    for path in paths:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        for index, row in convert_public.rows_from_file(path):
            try:
                provenance = {
                    "publisher": "Google", "license": "CC-BY-4.0",
                    "license_url": "https://creativecommons.org/licenses/by/4.0/",
                    "source_url": REPOSITORY, "revision": REVISION,
                    "source_file": path.name, "row_index": index,
                    "input_sha256": digest, "mapping_version": "r1-flashlight-v1",
                    "revision_evidence": "Pinned original verified against publisher LFS SHA256",
                    "attribution": "Google, Mobile Actions; flashlight actions mapped to Jarvis toggle.",
                }
                result = convert_public.convert(row, "mobile-actions", prefix, schemas,
                    convert_public.FLASHLIGHT, provenance, held_out)
                reason = review_reason(result["text"])
                if reason:
                    raise convert_public.Rejected(reason)
                result["provenance"]["review"] = {
                    "method": "Agent inspected all 509 unique heldout-filtered source texts; conservative semantic filter",
                    "policy": "Explicit flashlight/torch only; exclude ambiguous light targets and how-to questions",
                    "human_reviewed": False,
                }
                key = convert_public.dataset.holdout_key(result["text"])
                if key in seen:
                    raise convert_public.Rejected("duplicate_user")
                seen.add(key)
                accepted.append(result)
            except convert_public.Rejected as error:
                counts[str(error)] += 1
    return accepted, dict(counts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=SOURCE_DIR)
    parser.add_argument("--out", type=Path, default=SOURCE_DIR / "public_candidates.jsonl")
    args = parser.parse_args()
    prefix, _ = load_contract()
    held_out = {convert_public.dataset.holdout_key(json.loads(line)["text"])
        for name in ("cases.jsonl", "cases_v2.jsonl")
        for line in Path(__file__).with_name(name).read_text().splitlines() if line.strip()}
    rows, rejected = candidates(prefix, held_out, args.source_dir)
    with args.out.open("x") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(json.dumps({"accepted": len(rows), "rejected": rejected, "out": str(args.out)}))


if __name__ == "__main__":
    main()
