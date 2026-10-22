#!/usr/bin/env python3
"""CPU-only sherpa-onnx evaluation on local 16 kHz mono PCM16 WAV files."""

import argparse
import csv
from datetime import datetime, timezone
from pathlib import Path
import re
import sys
import time
import unicodedata
import wave

import numpy as np
import sherpa_onnx


KINDS = ("zipformer-streaming", "zipformer-offline", "nemo-transducer",
         "whisper", "paraformer", "moonshine")


def normalize(text):
    text = unicodedata.normalize("NFKC", text).casefold().replace("’", "'")
    text = "".join(c for c in text if not unicodedata.category(c).startswith("P"))
    return text.split()


def distance(reference, hypothesis):
    previous = list(range(len(hypothesis) + 1))
    for i, word in enumerate(reference, 1):
        current = [i]
        for j, other in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[j] + 1,
                               previous[j - 1] + (word != other)))
        previous = current
    return previous[-1]


def read_wav(path):
    with wave.open(str(path), "rb") as wav:
        if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth(),
                wav.getcomptype()) != (16000, 1, 2, "NONE"):
            raise ValueError(f"{path}: expected 16000 Hz mono PCM16 WAV")
        count = wav.getnframes()
        data = wav.readframes(count)
    if not count or len(data) != count * 2:
        raise ValueError(f"{path}: empty or truncated audio")
    return np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0


def references(path, files):
    if path is None:
        return {}
    refs = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        for number, row in enumerate(csv.reader(source, delimiter="\t"), 1):
            if not row:
                continue
            if len(row) != 2 or not row[0] or row[0] in refs:
                raise ValueError(f"{path}:{number}: need unique filename<TAB>text")
            refs[row[0]] = row[1]
    names = {p.name for p in files}
    if names != refs.keys():
        raise ValueError(f"Reference mismatch: missing={sorted(names - refs.keys())}, "
                         f"extra={sorted(refs.keys() - names)}")
    return refs


def model_files(args):
    overrides = {}
    for entry in args.file:
        role, separator, value = entry.partition("=")
        if not separator or role in overrides:
            raise ValueError("--file requires a unique ROLE=PATH")
        overrides[role] = Path(value)
    roles = {
        "zipformer-streaming": ["encoder", "decoder", "joiner"],
        "zipformer-offline": ["encoder", "decoder", "joiner"],
        "nemo-transducer": ["encoder", "decoder", "joiner"],
        "whisper": ["encoder", "decoder"],
        "paraformer": ["model"],
        "moonshine": ["preprocess", "encode", "uncached_decode", "cached_decode"],
    }[args.kind] + ["tokens"]
    if overrides.keys() - set(roles):
        raise ValueError(f"Allowed --file roles: {', '.join(roles)}")
    selected = {}
    for role in roles:
        if role in overrides:
            path = overrides[role]
            path = path if path.is_absolute() else args.model_dir / path
        else:
            suffix = ".txt" if role == "tokens" else ".onnx"
            candidates = sorted(p for p in args.model_dir.glob(f"*{suffix}")
                                if re.search(rf"(^|[-_.]){role}([-_.]|$)", p.stem))
            quantized = [p for p in candidates if "int8" in p.name]
            if quantized:
                candidates = quantized
            if len(candidates) != 1:
                raise ValueError(f"{role}: found {len(candidates)} candidates; "
                                 f"select with --file {role}=filename")
            path = candidates[0]
        if not path.is_file():
            raise ValueError(f"Missing model file: {path}")
        selected[role] = str(path)
    return selected


def recognizer(args, files):
    common = dict(num_threads=args.threads, provider="cpu", **files)
    if args.kind == "zipformer-streaming":
        return sherpa_onnx.OnlineRecognizer.from_transducer(**common)
    offline = sherpa_onnx.OfflineRecognizer
    if args.kind in ("zipformer-offline", "nemo-transducer"):
        model_type = "nemo_transducer" if args.kind == "nemo-transducer" else "transducer"
        return offline.from_transducer(**common, model_type=model_type)
    if args.kind == "whisper":
        return offline.from_whisper(**common, language="en", task="transcribe")
    if args.kind == "paraformer":
        common["paraformer"] = common.pop("model")
        return offline.from_paraformer(**common)
    for source, dest in [("preprocess", "preprocessor"), ("encode", "encoder"),
                         ("uncached_decode", "uncached_decoder"),
                         ("cached_decode", "cached_decoder")]:
        common[dest] = common.pop(source)
    return offline.from_moonshine(**common)


def decode(engine, samples, streaming):
    stream = engine.create_stream()
    if streaming:
        for start in range(0, len(samples), 1600):
            stream.accept_waveform(16000, samples[start:start + 1600])
            while engine.is_ready(stream):
                engine.decode_stream(stream)
        stream.accept_waveform(16000, np.zeros(4800, dtype=np.float32))
        stream.input_finished()
        while engine.is_ready(stream):
            engine.decode_stream(stream)
        return engine.get_result(stream).strip()
    stream.accept_waveform(16000, samples)
    engine.decode_stream(stream)
    return stream.result.text.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wav-dir", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--kind", choices=KINDS, required=True)
    parser.add_argument("--ref", type=Path)
    parser.add_argument("--csv", type=Path, help="New CSV path; existing files are never overwritten")
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--file", action="append", default=[], metavar="ROLE=PATH",
                        help="Override model component relative to --model-dir (repeatable)")
    args = parser.parse_args()
    if args.threads < 1:
        parser.error("--threads must be positive")
    paths = sorted(p for p in args.wav_dir.glob("*") if p.suffix.lower() == ".wav")
    if not paths:
        parser.error("--wav-dir contains no WAV files (nonrecursive)")
    refs = references(args.ref, paths)
    files = model_files(args)
    # Validate the whole corpus before loading a model or writing partial results.
    for path in paths:
        read_wav(path)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = args.csv or Path("training/outputs/stt_eval") / f"{args.kind}-{stamp}.csv"
    if output.exists():
        raise ValueError(f"CSV already exists: {output}")
    print(f"sherpa-onnx {sherpa_onnx.__version__}; CPU; threads={args.threads}")
    print(f"Model files: {files}")
    start = time.perf_counter()
    engine = recognizer(args, files)
    load_seconds = time.perf_counter() - start
    print(f"Model load: {load_seconds:.3f}s")
    streaming = args.kind == "zipformer-streaming"
    decode(engine, read_wav(paths[0]), streaming)
    rows = []
    errors = words = correct = 0
    for path in paths:
        samples = read_wav(path)
        seconds = len(samples) / 16000
        start = time.perf_counter()
        text = decode(engine, samples, streaming)
        elapsed = time.perf_counter() - start
        row = dict(file=path.name, text=text, audio_seconds=seconds,
                   decode_seconds=elapsed, rtf=elapsed / seconds,
                   reference="", word_errors="", reference_words="", wer="", sentence_correct="",
                   kind=args.kind, model_dir=str(args.model_dir), threads=args.threads,
                   sherpa_version=sherpa_onnx.__version__, load_seconds=load_seconds,
                   model_files=";".join(f"{k}={v}" for k, v in files.items()))
        if refs:
            reference, hypothesis = normalize(refs[path.name]), normalize(text)
            edits = distance(reference, hypothesis)
            row.update(reference=refs[path.name], word_errors=edits,
                       reference_words=len(reference),
                       wer=edits / len(reference) if reference else "",
                       sentence_correct=int(reference == hypothesis))
            errors += edits
            words += len(reference)
            correct += reference == hypothesis
        rows.append(row)
        print(f"{path.name}\t{text}\t{elapsed:.3f}s\tRTF={elapsed / seconds:.3f}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    total_audio = sum(row["audio_seconds"] for row in rows)
    total_decode = sum(row["decode_seconds"] for row in rows)
    print(f"TOTAL files={len(rows)} audio={total_audio:.3f}s decode={total_decode:.3f}s "
          f"RTF={total_decode / total_audio:.3f}")
    if refs:
        wer = f"{errors / words:.2%}" if words else "undefined (no reference words)"
        print(f"WER={wer} ({errors}/{words}); sentence accuracy={correct / len(rows):.2%} "
              f"({correct}/{len(rows)})")
    print(f"CSV: {output}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, wave.Error, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
