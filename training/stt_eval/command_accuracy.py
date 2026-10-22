#!/usr/bin/env python3
"""Private command-corpus benchmark; all generated artifacts stay outside the repo."""
import argparse
import importlib.util
import hashlib
import json
from pathlib import Path
import resource
import re
import subprocess
import sys
import time
import wave


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--root", type=Path, default=Path("/private/tmp/jimi-stt"))
    p.add_argument("--references", action="store_true")
    p.add_argument("--count", type=int, default=20)
    p.add_argument("--reference-count", type=int, default=20)
    p.add_argument("--kind")
    p.add_argument("--model-dir", type=Path)
    p.add_argument("--name")
    args = p.parse_args()
    if not args.references and (not args.kind or not args.model_dir or
                               not args.name or not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", args.name)):
        p.error("Candidate runs need --kind, --model-dir and a lowercase slug --name")
    root = args.root.resolve()
    repo = Path(__file__).resolve().parents[2]
    if root == repo or repo in root.parents:
        p.error("Private audio/transcripts/results must be outside the repository")
    files = sorted((root / "commands").glob("*.wav"))
    if not files or args.count < 1 or args.reference_count < 1:
        p.error("Need command WAVs and positive counts")
    ref_count = min(args.reference_count, len(files))
    count = min(args.count, len(files))
    reference_files = [files[round(i * (len(files)-1) / max(1, ref_count-1))]
                       for i in range(ref_count)]
    reference_names = {f.name for f in reference_files}
    files = [files[i] for i in sorted({round(i * (len(files)-1) / max(1, count-1))
                                     for i in range(count)})]
    refs = root / "references"
    if refs.resolve() != root / "references":
        p.error("Reference directory must not be a symlink")
    refs.mkdir(exist_ok=True)
    if args.references:
        for n, f in enumerate(reference_files, 1):
            out = refs / f.stem
            if out.with_suffix(".txt").exists():
                continue
            with wave.open(str(f)) as w:
                context = min(1500, ((w.getnframes() // 320 + 227) // 128) * 128)
            cmd = ["whisper-cli", "-m", str(root / "models/ggml-large-v3-turbo-q5_0.bin"),
                   "-ng", "-t", "2", "-l", "en", "-ac", str(context),
                   "-otxt", "-of", str(out), str(f)]
            with out.with_suffix(".log").open("a") as log:
                log.write(json.dumps({"command": cmd}) + "\n")
                log.flush()
                subprocess.run(cmd, stdout=log, stderr=log, check=True, timeout=180)
            print(f"Reference {n}/{len(reference_files)}", flush=True)
        return
    spec = importlib.util.spec_from_file_location("eval_stt", Path(__file__).with_name("eval_stt.py"))
    bench = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bench)
    model_args = argparse.Namespace(kind=args.kind, model_dir=args.model_dir, file=[], threads=2)
    started = time.perf_counter()
    if args.kind == "sense-voice":
        import sherpa_onnx
        model_files = {"model": str(next(args.model_dir.glob("*int8.onnx"))),
                       "tokens": str(args.model_dir / "tokens.txt")}
        engine = sherpa_onnx.OfflineRecognizer.from_sense_voice(**model_files, num_threads=2,
                                                              language="en", use_itn=False)
    else:
        model_files = bench.model_files(model_args)
        engine = bench.recognizer(model_args, model_files)
    load = time.perf_counter() - started
    streaming = args.kind == "zipformer-streaming"
    def decode(samples):
        if not streaming:
            return bench.decode(engine, samples, False)
        stream = engine.create_stream()
        stream.accept_waveform(16000, bench.np.zeros(8000, dtype=bench.np.float32))
        for start in range(0, len(samples), 1600):
            stream.accept_waveform(16000, samples[start:start+1600])
            while engine.is_ready(stream):
                engine.decode_stream(stream)
        stream.accept_waveform(16000, bench.np.zeros(8000, dtype=bench.np.float32))
        stream.input_finished()
        while engine.is_ready(stream):
            engine.decode_stream(stream)
        return engine.get_result(stream).strip()
    decode(bench.read_wav(files[0]))
    rows = []
    for f in files:
        samples = bench.read_wav(f)
        start = time.perf_counter()
        hyp = decode(samples)
        elapsed = time.perf_counter() - start
        ref_path = refs / (f.stem + ".txt")
        ref = ref_path.read_text().strip() if ref_path.exists() and f.name in reference_names else None
        rows.append(dict(file=f.name, seconds=len(samples)/16000, decode=elapsed,
                         hypothesis=hyp, reference=ref,
                         words=len(bench.normalize(ref)) if ref is not None else None,
                         errors=bench.distance(bench.normalize(ref), bench.normalize(hyp))
                         if ref is not None else None))
    scored = [r for r in rows if r["reference"] is not None]
    reference_words = sum(r["words"] for r in scored)
    reference_errors = sum(r["errors"] for r in scored)
    summary = dict(model=args.name, clips=len(rows), reference_clips=len(scored),
                   expected_reference_clips=len(reference_names),
                   unscored_reference_clips=len(reference_names)-len(scored),
                   reference_words=reference_words, reference_errors=reference_errors,
                   load_seconds=load, audio_seconds=sum(r["seconds"] for r in rows),
                   rtf=sum(r["decode"] for r in rows)/sum(r["seconds"] for r in rows),
                   peak_rss_mb=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss *
                   (1 if sys.platform == "darwin" else 1024) / 1e6,
                   selected_model_mb=sum(Path(f).stat().st_size for f in model_files.values())/1e6,
                   wer=reference_errors/reference_words if reference_words else None)
    result = root / (args.name + ".json")
    metadata = dict(sherpa_version=bench.sherpa_onnx.__version__, threads=2, provider="cpu",
                    decoding="greedy_search", streaming_tail_padding_seconds=0.5 if streaming else 0,
                    streaming_lead_padding_seconds=0.5 if streaming else 0,
                    reference_model="whisper.cpp large-v3-turbo q5_0; CPU 2 threads English",
                    reference_context="duration + padding rounded to 128 frames, capped1500; see reference logs",
                    model_sha256={role: hashlib.file_digest(open(path, "rb"), "sha256").hexdigest()
                                  for role, path in model_files.items()})
    with result.open("x") as output:
        json.dump(dict(summary=summary, metadata=metadata, rows=rows), output, indent=2)
    print(json.dumps(summary), flush=True)


if __name__ == "__main__":
    main()
