"""CPU Kaggle job: extract bounded licensed public subsets, no private inputs."""
import csv
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import random
import subprocess
import sys
import tarfile


def main():
    if not Path('/kaggle/input').is_dir():
        raise SystemExit('Run this data preparation job on Kaggle')
    subprocess.run([sys.executable, '-m', 'pip', 'install', '-q', 'soundfile', 'scipy'], check=True)
    import soundfile as sf
    from scipy.signal import resample_poly
    from math import gcd
    rng = random.Random(42)
    output = Path('/kaggle/temp/stt-public')
    output.mkdir(parents=True, exist_ok=False)
    stats = {}
    for source, filename in [('common_voice', 'cv-valid-train.csv'), ('svarah', 'meta_speaker_stats.csv')]:
        matches = []
        frontier = [Path('/kaggle/input')]
        for _ in range(5):
            following = []
            for directory in frontier:
                if (directory / filename).is_file():
                    matches.append(directory / filename)
                following.extend(p for p in directory.iterdir()
                                 if p.name != 'audio' and not p.name.startswith('cv-') and p.is_dir())
            frontier = following
        if len(matches) != 1:
            raise ValueError(f'Expected exactly one {filename}')
        manifest = matches[0]
        print(f'Loading {source} metadata', flush=True)
        with manifest.open() as f:
            rows = list(csv.DictReader(f))
        if source == 'common_voice':
            rows = [r for r in rows if any(x in r['accent'].lower() for x in
                    ('india', 'south asia', 'bangladesh', 'pakistan', 'sri lanka', 'nepal', 'bengal'))]
        rng.shuffle(rows)
        dest = output / source
        dest.mkdir()
        count = 0
        seconds = 0
        def materialize(row):
                if source == 'common_voice':
                    accent = row['accent'].lower()
                    relative = Path(row['filename'])
                    path = manifest.parent / relative
                    if not path.is_file():
                        path = manifest.parent / relative.parts[0] / relative
                    group = [row.get(k, '') for k in ('age', 'gender', 'accent')]
                    license_name = 'CC0-1.0'
                    url = 'https://www.kaggle.com/datasets/mozillaorg/common-voice'
                else:
                    path = manifest.parent / row['audio_filepath']
                    accent = row['primary_language']
                    group = [row.get(k, '') for k in ('gender', 'age-group', 'primary_language',
                             'native_place_state', 'native_place_district', 'highest_qualification',
                             'job_category', 'occupation_domain')]
                    license_name = 'CC-BY-4.0; upstream AI4Bharat, Javed et al. 2023'
                    url = 'https://github.com/AI4Bharat/Svarah'
                if not path.resolve().is_relative_to(manifest.parent.resolve()):
                    raise ValueError('Unexpected corpus path escape')
                info = sf.info(path)
                if not 0.3 <= info.duration <= 25 or not row['text'].strip():
                    return None
                samples, rate = sf.read(path, dtype='float32', always_2d=True)
                divisor = gcd(rate, 16000)
                samples = resample_poly(samples.mean(axis=1), 16000 // divisor, rate // divisor)
                name = hashlib.sha256(str(path.relative_to(manifest.parent)).encode()).hexdigest() + '.wav'
                sf.write(dest / name, samples, 16000, subtype='PCM_16')
                return (dict(audio=name, text=row['text'], speaker=json.dumps(group),
                             accent=accent, license=license_name, url=url), len(samples) / 16000)
        with (dest / 'manifest.csv').open('x', newline='') as f, ThreadPoolExecutor(max_workers=8) as pool:
            writer = csv.DictWriter(f, fieldnames=['audio', 'text', 'speaker', 'accent', 'license', 'url'])
            writer.writeheader()
            for start in range(0, len(rows), 32):
                for result in pool.map(materialize, rows[start:start + 32]):
                    if result is None:
                        continue
                    record, duration = result
                    writer.writerow(record)
                    count += 1
                    seconds += duration
                print(f'{source}: {count} clips, {seconds:.0f} seconds', flush=True)
                if seconds >= 2 * 3600:
                    break
        if not count:
            raise ValueError(f'No eligible {source} clips')
        stats[source] = {'clips': count, 'seconds': seconds,
                         'grouping': 'demographic proxy; stable speaker IDs unavailable'}
    with tarfile.open('/kaggle/working/public-subsets.tar.gz', 'w:gz') as archive:
        for source in stats:
            archive.add(output / source, arcname=source)
    Path('/kaggle/working/subset-summary.json').write_text(json.dumps(stats, indent=2))
    print(json.dumps(stats), flush=True)


if __name__ == '__main__':
    main()
