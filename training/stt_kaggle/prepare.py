"""Build an upload-only directory from explicitly public corpus manifests."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'training/data/stt'
SOURCES = ('common_voice', 'svarah', 'nptel', 'synthetic')
ACCENTS = ('india', 'south asia', 'bangladesh', 'pakistan', 'sri lanka', 'nepal', 'bengal')


def contained(root, relative):
    path = root / relative
    if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('Symlinks/path escapes are not public inputs')
    return path


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--mode', choices=['smoke', 'real'], required=True)
    p.add_argument('--owner', required=True)
    p.add_argument('--hours-per-source', type=float, default=5)
    p.add_argument('--synthetic-smoke', action='store_true')
    p.add_argument('--run-id', help='Fresh run directory suffix')
    p.add_argument('--reuse-prepared', help='Reuse an immutable full smoke dataset for the real run')
    args = p.parse_args()
    if args.synthetic_smoke and args.mode != 'smoke':
        p.error('Synthetic-only data is allowed only for a diagnostic smoke run')
    import numpy as np
    import soundfile as sf
    from scipy.signal import resample_poly
    from math import gcd
    public = DATA / 'public'
    run_id = args.run_id or args.mode
    if not run_id.replace('-', '').isalnum():
        p.error('run-id must contain only letters, numbers and hyphens')
    output = DATA / ('upload-' + run_id)
    output.mkdir(parents=True, exist_ok=False)
    if args.reuse_prepared:
        if args.mode != 'real' or not args.reuse_prepared.replace('-', '').isalnum():
            p.error('reuse-prepared requires real mode and a valid smoke run ID')
        previous = DATA / ('upload-' + args.reuse_prepared)
        if json.loads((previous / 'run_mode.json').read_text())['mode'] != 'smoke':
            raise ValueError('Expected a smoke dataset')
        counts = json.loads((previous / 'provenance.json').read_text())
        if set(counts) != set(SOURCES) or not all(counts.values()):
            raise ValueError('Full data mixture required')
        for path in previous.iterdir():
            if path.name == 'run_mode.json':
                continue
            if path.is_symlink() or not path.is_file():
                raise ValueError('Prepared data must contain only regular files')
            os.link(path, output / path.name)
        (output / 'run_mode.json').write_text(json.dumps({'mode': 'real'}))
        print(json.dumps({'output': str(output), 'counts': counts, 'reused': args.reuse_prepared}))
        return
    rng = random.Random(42)
    noise = []
    for name in (() if args.synthetic_smoke else ('musan', 'demand')):
        folder = public / name
        files = sorted(folder.rglob('*.wav'))
        if not files:
            raise ValueError(f'Missing public/{name} WAV noise')
        noise += [(folder, f.relative_to(folder)) for f in files]

    def audio(root, relative, max_seconds=None):
        path = contained(root, relative)
        with sf.SoundFile(path) as f:
            rate = f.samplerate
            frames = -1 if max_seconds is None else int(max_seconds * rate)
            if frames > 0 and len(f) > frames:
                f.seek(rng.randrange(len(f) - frames + 1))
            samples = f.read(frames=frames, dtype='float32', always_2d=True)
        samples = samples.mean(axis=1)
        divisor = gcd(rate, 16000)
        return resample_poly(samples, 16000 // divisor, rate // divisor)

    # Compare locally; no phone paths, samples or digests enter the upload.
    private = Path('/private/tmp/jimi-stt/commands')
    held_out = {hashlib.sha256(audio(private, path.name).tobytes()).hexdigest()
                for path in private.glob('*.wav')}

    splits = {'train': [], 'dev': []}
    counts = {}
    seen = set()
    for source in (('synthetic',) if args.synthetic_smoke else SOURCES):
        folder = public / source
        with contained(folder, 'manifest.csv').open() as f:
            rows = list(csv.DictReader(f))
        rng.shuffle(rows)
        seconds = 0
        counts[source] = 0
        for row in rows:
            if not row.get('text', '').strip():
                continue
            if source == 'common_voice' and not any(x in row.get('accent', '').lower() for x in ACCENTS):
                continue
            if not all(row.get(k, '').strip() for k in ('text', 'speaker', 'license', 'url', 'audio')):
                raise ValueError('Each public row requires audio,text,speaker,license,url (and CV accent)')
            info = sf.info(contained(folder, row['audio']))
            if not 0.3 <= info.duration <= 25:
                continue
            samples = audio(folder, row['audio'])
            if not 0.3 <= len(samples) / 16000 <= 25:
                continue
            digest = hashlib.sha256(samples.tobytes()).hexdigest()
            if digest in held_out:
                raise ValueError('Phone recording detected in public inputs')
            if digest in seen:
                continue
            seen.add(digest)
            # Speakers are disjoint; synthetic variants of a sentence stay together.
            group = row['text'].strip().lower() if source == 'synthetic' else row['speaker']
            split = 'dev' if int(hashlib.sha256((source + ':' + group).encode()).hexdigest()[:8], 16) % 10 == 0 else 'train'
            variants = [samples]
            if split == 'train' and noise:
                noise_root, relative = rng.choice(noise)
                background = audio(noise_root, relative, max_seconds=25)
                if not len(background):
                    raise ValueError('Empty noise')
                background = np.tile(background, 1 + len(samples) // len(background))
                start = rng.randrange(len(background) - len(samples) + 1)
                background = background[start:start + len(samples)]
                snr = rng.uniform(5, 20)
                scale = np.sqrt(np.mean(samples ** 2) / max(np.mean(background ** 2), 1e-10)) * 10 ** (-snr / 20)
                mixed = samples + background * scale
                variants.append(mixed / max(1.0, float(np.max(np.abs(mixed)))))
            for variant, wave in enumerate(variants):
                relative = f'{digest}-{variant}.wav'
                sf.write(output / relative, wave, 16000, subtype='PCM_16')
                splits[split].append({'audio': relative, 'text': row['text'], 'source': source,
                                      'group': hashlib.sha256(group.encode()).hexdigest(),
                                      'license': row['license'], 'url': row['url']})
            counts[source] += 1
            seconds += len(samples) / 16000
            if seconds >= args.hours_per_source * 3600:
                break
        if not counts[source]:
            raise ValueError(f'No usable {source} rows')
    for split, rows in splits.items():
        if not rows:
            raise ValueError(f'Empty {split}; provide more speakers/commands')
        (output / f'{split}.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in rows))
    (output / 'run_mode.json').write_text(json.dumps({'mode': args.mode}))
    (output / 'provenance.json').write_text(json.dumps(counts, indent=2))
    (output / 'dataset-metadata.json').write_text(json.dumps({
        'title': 'Jimi STT public', 'id': args.owner + '/jimi-stt-public',
        'licenses': [{'name': 'other'}]}, indent=2))
    print(json.dumps({'output': str(output), 'counts': counts}))


if __name__ == '__main__':
    main()
