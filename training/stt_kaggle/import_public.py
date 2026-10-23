"""Index extracted public corpora without reading phone recordings."""
import argparse
import csv
import json
import re
from pathlib import Path
from prepare import DATA, ACCENTS, contained


def main():
    p = argparse.ArgumentParser()
    p.add_argument('source', choices=['common_voice', 'nptel'])
    p.add_argument('--license', required=True, help='License from the downloaded release')
    p.add_argument('--url', required=True, help='Exact public release URL')
    args = p.parse_args()
    root = DATA / 'public' / args.source
    rows = []
    if args.source == 'common_voice':
        for path in root.rglob('validated.tsv'):
            contained(root, path.relative_to(root))
            with path.open() as f:
                for row in csv.DictReader(f, delimiter='\t'):
                    accent = row.get('accents') or row.get('accent') or ''
                    if any(x in accent.lower() for x in ACCENTS):
                        wav = path.parent / 'clips' / row['path']
                        rows.append(dict(audio=wav.relative_to(root).as_posix(), text=row['sentence'],
                                         speaker=row['client_id'], accent=accent))
    else:
        for path in root.rglob('corrected_txt/*.txt'):
            base = path.parent.parent
            metadata = json.loads((base / 'metadata' / (path.stem + '.json')).read_text())['metadata']
            # Course description identifies the lecturer across videos when present.
            description = metadata.get('description', '')
            lecturer = re.search(r'\bby\s+([^\n.]+(?:\.[^\n.]+)?)', description, re.I)
            group = lecturer.group(1).strip().lower() if lecturer else description.strip().lower()
            if not group:
                continue
            wav = base / 'wav' / (path.stem + '.wav')
            rows.append(dict(audio=wav.relative_to(root).as_posix(), text=path.read_text().strip(),
                             speaker=group, accent='Indian English'))
        # NPTEL2020 public subset uses LibriSpeech-style trans.txt files.
        for path in root.rglob('*.trans.txt'):
            contained(root, path.relative_to(root))
            for line in path.read_text().splitlines():
                key, text = line.split(maxsplit=1)
                wav = path.parent / (key + '.wav')
                rows.append(dict(audio=wav.relative_to(root).as_posix(), text=text,
                                 speaker=key.split('-')[0], accent='Indian English'))
    if not rows:
        raise ValueError('No matching rows. Inspect extracted corpus schema; do not invent labels.')
    with (root / 'manifest.csv').open('x', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=['audio', 'text', 'speaker', 'accent', 'license', 'url'])
        writer.writeheader()
        for row in rows:
            contained(root, row['audio'])
            writer.writerow(dict(row, license=args.license, url=args.url))
    print(f'Indexed {len(rows)} public rows')


if __name__ == '__main__':
    main()
