"""Prepare local data for wake word training.

  python prepare_data.py acav      # fix header of the partial ACAV100M download -> acav_part.npy
  python prepare_data.py noise     # AudioSet parquet -> 16 kHz WAV background clips
  python prepare_data.py user <recording.wav> <out_dir> [--negative] [--from S] [--to S]
        # cut a phone recording (or part of it) into clips by loudness.
        # positive: one clip per "Hey Jimi"; negative (--negative): 2 s chunks of normal talk
"""
import io
import sys
from pathlib import Path

import numpy as np
import scipy.io.wavfile as wavfile

ROOT = Path(__file__).resolve().parents[2]
WW = ROOT / "models/wakeword"
ROW_SHAPE = (16, 96)
ROW_BYTES = 16 * 96 * 2  # float16


def fix_acav():
    raw = WW / "data/acav_part.raw"
    header_len = 128
    n_rows = (raw.stat().st_size - header_len) // ROW_BYTES
    out = WW / "data/acav_part.npy"
    arr = np.lib.format.open_memmap(out, mode="w+", dtype=np.float16, shape=(n_rows, *ROW_SHAPE))
    src = np.memmap(raw, dtype=np.float16, mode="r", offset=header_len, shape=(n_rows, *ROW_SHAPE))
    for i in range(0, n_rows, 50_000):
        arr[i:i + 50_000] = src[i:i + 50_000]
    arr.flush()
    print(f"{out}: {n_rows} rows (~{n_rows * 1.28 / 3600:.0f} h of audio features)")


def audioset_to_wav():
    import pyarrow.parquet as pq
    import soundfile as sf
    from scipy.signal import resample_poly
    out = WW / "noise/wav"
    out.mkdir(parents=True, exist_ok=True)
    n = 0
    for pf in sorted((WW / "noise").glob("*.parquet")):
        table = pq.read_table(pf, columns=["audio"])
        for row in table.column("audio").to_pylist():
            audio, sr = sf.read(io.BytesIO(row["bytes"]), dtype="float32")
            if audio.ndim > 1:
                audio = audio.mean(axis=1)
            audio = resample_poly(audio, 16000, sr)
            wavfile.write(out / f"as_{n:05d}.wav", 16000, (np.clip(audio, -1, 1) * 32767).astype(np.int16))
            n += 1
    print(f"{n} background clips -> {out}")


def cut_user(recording: str, out_dir: str, negative: bool, start_s: float = 0, end_s: float = 1e9):
    sr, a = wavfile.read(recording)
    assert sr == 16000, "convert first: ffmpeg -i in.aac -ar 16000 -ac 1 out.wav"
    a = a.astype(np.float32)[int(start_s * sr): int(min(end_s * sr, len(a)))]
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    stem = Path(recording).stem
    if negative:
        n = 0
        for i in range(0, len(a) - 32000, 32000):
            wavfile.write(out / f"{stem}_neg_{n:04d}.wav", 16000, a[i:i + 32000].astype(np.int16))
            n += 1
        print(f"{n} negative 2 s chunks -> {out}")
        return
    # Loudness in 50 ms frames; a phrase = loud run of 0.25–3.6 s separated by >= 0.6 s quiet.
    # Low threshold so far (quiet) phrases are found too.
    hop = 800
    rms = np.array([np.sqrt(np.mean(a[i:i + hop] ** 2)) for i in range(0, len(a) - hop, hop)])
    thr = np.median(rms) * 1.5
    loud = rms > thr
    segs, start, quiet = [], None, 0
    for k, on in enumerate(loud):
        if on:
            start = k if start is None else start
            quiet = 0
        elif start is not None:
            quiet += 1
            if quiet >= 12:
                segs.append((start, k - quiet + 1))
                start, quiet = None, 0
    n = 0
    for s, e in segs:
        dur = (e - s) * hop / 16000
        if 0.5 <= dur <= 3.6:
            clip = a[max(s * hop - 2400, 0): e * hop + 2400]
            wavfile.write(out / f"{stem}_pos_{n:03d}.wav", 16000, clip.astype(np.int16))
            print(f"  clip {n}: {start_s + s * hop / 16000:.1f}s  {dur:.2f}s")
            n += 1
    print(f"{n} positive clips -> {out}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "acav":
        fix_acav()
    elif cmd == "noise":
        audioset_to_wav()
    elif cmd == "user":
        opt = lambda name, default: float(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default
        cut_user(sys.argv[2], sys.argv[3], "--negative" in sys.argv, opt("--from", 0), opt("--to", 1e9))
    else:
        sys.exit(__doc__)
