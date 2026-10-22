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


def denoiser(args):
    if args.denoise_model is None:
        return None
    if not args.denoise_model.is_file():
        raise ValueError(f"Missing denoise model: {args.denoise_model}")
    config = sherpa_onnx.OfflineSpeechDenoiserConfig(
        model=sherpa_onnx.OfflineSpeechDenoiserModelConfig(
            gtcrn=sherpa_onnx.OfflineSpeechDenoiserGtcrnModelConfig(
                model=str(args.denoise_model)),
            num_threads=args.threads, provider="cpu", debug=False))
    if not config.validate():
        raise ValueError("Invalid GTCRN denoiser configuration")
    return sherpa_onnx.OfflineSpeechDenoiser(config)


def noise_segment(samples, noise, rng):
    if noise is None:
        return rng.standard_normal(len(samples)).astype(np.float32)
    start = int(rng.integers(len(noise)))
    return noise[(np.arange(len(samples)) + start) % len(noise)]


def mix_noise(samples, noise, snr):
    signal_power = float(np.mean(samples.astype(np.float64) ** 2))
    noise_power = float(np.mean(noise.astype(np.float64) ** 2))
    if signal_power <= 0 or noise_power <= 0:
        raise ValueError("SNR mixing requires non-silent speech and noise")
    scale = np.sqrt(signal_power / noise_power) * 10 ** (-snr / 20)
    mixed = samples.astype(np.float64) + noise * scale
    # Scale the complete mixture, preserving SNR without clipping.
    mixed /= max(1.0, float(np.max(np.abs(mixed))))
    return mixed.astype(np.float32)


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
    parser.add_argument("--denoise-model", type=Path, help="GTCRN ONNX model")
    parser.add_argument("--noise-dir", type=Path, help="16 kHz mono PCM16 noise WAV directory")
    parser.add_argument("--snr", type=float, nargs="+", help="Noise SNR in dB (default with --noise-dir: 0 5 10)")
    parser.add_argument("--seed", type=int, default=0, help="Deterministic noise seed (default: 0)")
    args = parser.parse_args()
    if args.threads < 1:
        parser.error("--threads must be positive")
    if args.seed < 0:
        parser.error("--seed must be nonnegative")
    if args.noise_dir is not None and not args.noise_dir.is_dir():
        parser.error("--noise-dir must be an existing directory")
    snrs = args.snr if args.snr is not None else ([0.0, 5.0, 10.0] if args.noise_dir else [])
    if any(not np.isfinite(snr) or abs(snr) > 100 for snr in snrs):
        parser.error("--snr must be finite and between -100 and 100 dB")
    if len(set(snrs)) != len(snrs):
        parser.error("--snr values must be unique")
    noise_paths = sorted(p for p in args.noise_dir.glob("*") if p.suffix.lower() == ".wav") if args.noise_dir else []
    for path in noise_paths:
        read_wav(path)
    if snrs and not noise_paths:
        print("No noise WAVs supplied; generating deterministic white noise")
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
    start = time.perf_counter()
    enhancer = denoiser(args)
    denoise_load_seconds = time.perf_counter() - start if enhancer is not None else 0.0
    if enhancer is not None:
        enhancer.run(read_wav(paths[0]), 16000)
    rows = []
    rng = np.random.default_rng(args.seed)
    for path in paths:
        samples = read_wav(path)
        seconds = len(samples) / 16000
        conditions = [("clean", "", samples, "")]
        if snrs:
            noise_path = noise_paths[int(rng.integers(len(noise_paths)))] if noise_paths else None
            noise = noise_segment(samples, read_wav(noise_path) if noise_path else None, rng)
            noise_name = str(noise_path) if noise_path else "generated:white"
            for snr in snrs:
                mixed = mix_noise(samples, noise, snr)
                conditions.append(("noisy", snr, mixed, noise_name))
                if enhancer is not None:
                    conditions.append(("noisy+denoised", snr, mixed, noise_name))
        elif enhancer is not None:
            conditions.append(("clean+denoised", "", samples, ""))
        for condition, snr, audio, noise_name in conditions:
            denoise_seconds = 0.0
            if condition.endswith("+denoised"):
                start = time.perf_counter()
                enhanced = enhancer.run(audio, 16000)
                denoise_seconds = time.perf_counter() - start
                if enhanced.sample_rate != 16000:
                    raise ValueError(f"Unexpected denoiser output rate: {enhanced.sample_rate}")
                audio = np.asarray(enhanced.samples, dtype=np.float32)
                if not len(audio) or not np.isfinite(audio).all():
                    raise ValueError("Denoiser returned empty or non-finite audio")
            start = time.perf_counter()
            text = decode(engine, audio, streaming)
            elapsed = time.perf_counter() - start
            row = dict(file=path.name, text=text, audio_seconds=seconds,
                       decode_seconds=elapsed, rtf=elapsed / seconds,
                       reference="", word_errors="", reference_words="", wer="", sentence_correct="",
                       kind=args.kind, model_dir=str(args.model_dir), threads=args.threads,
                       sherpa_version=sherpa_onnx.__version__, load_seconds=load_seconds,
                       model_files=";".join(f"{k}={v}" for k, v in files.items()),
                       condition=condition, snr_db=snr, noise_file=noise_name, seed=args.seed,
                       denoise_model=str(args.denoise_model or ""),
                       denoise_load_seconds=denoise_load_seconds,
                       denoise_seconds=denoise_seconds, denoise_rtf=denoise_seconds / seconds,
                       total_rtf=(elapsed + denoise_seconds) / seconds)
            if refs:
                reference, hypothesis = normalize(refs[path.name]), normalize(text)
                edits = distance(reference, hypothesis)
                row.update(reference=refs[path.name], word_errors=edits,
                           reference_words=len(reference),
                           wer=edits / len(reference) if reference else "",
                           sentence_correct=int(reference == hypothesis))
            rows.append(row)
            print(f"{condition} snr={snr} {path.name}\t{text}\t{elapsed:.3f}s "
                  f"RTF={elapsed / seconds:.3f} denoise_RTF={denoise_seconds / seconds:.3f}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    for condition, snr in dict.fromkeys((r["condition"], r["snr_db"]) for r in rows):
        group = [r for r in rows if (r["condition"], r["snr_db"]) == (condition, snr)]
        total_audio = sum(row["audio_seconds"] for row in group)
        total_decode = sum(row["decode_seconds"] for row in group)
        total_denoise = sum(row["denoise_seconds"] for row in group)
        print(f"TOTAL {condition} snr={snr} files={len(group)} audio={total_audio:.3f}s "
              f"decode={total_decode:.3f}s RTF={total_decode / total_audio:.3f} "
              f"denoise={total_denoise:.3f}s denoise_RTF={total_denoise / total_audio:.3f} "
              f"total_RTF={(total_decode + total_denoise) / total_audio:.3f}")
        if refs:
            errors = sum(row["word_errors"] for row in group)
            words = sum(row["reference_words"] for row in group)
            correct = sum(row["sentence_correct"] for row in group)
            wer = f"{errors / words:.2%}" if words else "undefined (no reference words)"
            print(f"WER={wer} ({errors}/{words}); sentence accuracy={correct / len(group):.2%} "
                  f"({correct}/{len(group)})")
    print(f"CSV: {output}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, wave.Error, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
