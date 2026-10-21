"""Drop-in replacement for piper-sample-generator's `generate_samples`, used by openWakeWord's train.py.

Uses piper-tts (ONNX, works on macOS) with several multi-speaker English voices, so we get
many different voices and accents (incl. L2-ARCTIC non-native speakers) without a GPU.
Output: 16 kHz mono 16-bit WAV, silence trimmed.
"""
import os
import random
import uuid
import wave
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from scipy.signal import resample_poly

VOICE_DIR = Path(os.environ.get("PIPER_VOICE_DIR", Path(__file__).resolve().parents[2] / "models/wakeword/piper"))

_voice = None


def _espeak_dir() -> Path:
    # espeak-ng silently ignores data paths longer than ~160 chars (long venv paths),
    # so link the bundled data to a short path.
    from piper.phonemize_espeak import ESPEAK_DATA_DIR
    if len(str(ESPEAK_DATA_DIR)) < 100:
        return ESPEAK_DATA_DIR
    short = Path.home() / ".cache/jarvis-espeak-ng-data"
    if not short.exists():
        short.parent.mkdir(parents=True, exist_ok=True)
        try:
            short.symlink_to(ESPEAK_DATA_DIR)
        except FileExistsError:
            pass  # another worker made it
    return short


def _init_worker(voice_path):
    # One voice per worker process: each Piper model needs ~300 MB RAM (Mac has 8 GB).
    global _voice
    from piper import PiperVoice
    _voice = PiperVoice.load(voice_path, espeak_data_dir=_espeak_dir())


def _trim(audio: np.ndarray, sr: int, pad_ms: int = 80) -> np.ndarray:
    level = np.abs(audio)
    thresh = max(level.max() * 0.03, 1e-4)
    idx = np.where(level > thresh)[0]
    if len(idx) == 0:
        return audio
    pad = int(sr * pad_ms / 1000)
    return audio[max(idx[0] - pad, 0): idx[-1] + pad]


def _synth_one(job):
    text, path, length_scale, noise_scale, noise_w = job
    from piper import SynthesisConfig
    voice = _voice
    n_spk = voice.config.num_speakers
    cfg = SynthesisConfig(
        speaker_id=random.randrange(n_spk) if n_spk > 1 else None,
        length_scale=length_scale * random.uniform(0.9, 1.1),
        noise_scale=noise_scale * random.uniform(0.8, 1.1),
        noise_w_scale=noise_w * random.uniform(0.8, 1.1),
    )
    audio = np.concatenate([c.audio_float_array for c in voice.synthesize(text, cfg)])
    audio = _trim(audio, voice.config.sample_rate)
    audio = resample_poly(audio, 16000, voice.config.sample_rate)
    pcm = (np.clip(audio, -1, 1) * 32767 * random.uniform(0.5, 0.95)).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(pcm.tobytes())


def generate_samples(text, output_dir, max_samples, file_names=None, length_scales=(0.75, 1.0, 1.25),
                     noise_scales=(0.667,), noise_scale_ws=(0.8,), workers=None, **_ignored):
    """Same signature as piper-sample-generator. Extra kwargs (batch_size, ...) are ignored."""
    texts = [text] if isinstance(text, str) else list(text)
    os.makedirs(output_dir, exist_ok=True)
    jobs = []
    for i in range(max_samples):
        name = file_names[i] if file_names and i < len(file_names) else uuid.uuid4().hex + ".wav"
        jobs.append((texts[i % len(texts)], Path(output_dir) / name, random.choice(length_scales),
                     random.choice(noise_scales), random.choice(noise_scale_ws)))
    random.shuffle(jobs)
    voice_paths = sorted(VOICE_DIR.glob("*.onnx"))
    if not voice_paths:
        raise RuntimeError(f"No Piper voices in {VOICE_DIR}")
    workers = workers or 4
    _espeak_dir()  # create the short link once, before workers start
    done = 0
    for v, voice_path in enumerate(voice_paths):
        part = jobs[v::len(voice_paths)]
        with ProcessPoolExecutor(workers, initializer=_init_worker, initargs=(str(voice_path),)) as ex:
            for _ in ex.map(_synth_one, part, chunksize=16):
                done += 1
                if done % 1000 == 0:
                    print(f"generated {done}/{max_samples} -> {output_dir}", flush=True)
