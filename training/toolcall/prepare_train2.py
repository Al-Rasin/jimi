#!/usr/bin/env python3
"""Prepare a reproducible round-two mix without modifying source corpora."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import random
import re

import make_dataset as base
from make_round1 import family_key, is_fixture
from r1_contract import load_contract, validate_call


def read(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--old', type=Path, required=True)
    parser.add_argument('--round1', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        parser.error('output already exists; choose a fresh directory')
    source_paths = [args.old / 'train.jsonl', args.old / 'valid.jsonl']
    source_paths += [args.round1 / (name + '.jsonl') for name in ('train', 'dev', 'test')]
    old_train, old_valid, r1_train, dev, test = [read(path) for path in source_paths]
    fixtures = [Path(__file__).with_name(name) for name in ('cases.jsonl', 'cases_v2.jsonl', 'cases_real.jsonl')]
    fixture_keys = {base.holdout_key(row['text']) for path in fixtures for row in read(path)}
    held_keys = {base.holdout_key(row['text']) for row in dev + test + old_valid}
    held_families = {row['family'] for row in dev + test}
    prefix, schemas = load_contract()
    def render(row):
        validate_call(row['tool'], row['args'], schemas)
        row = dict(row)
        if 'context' not in row:
            match = re.search(r'\n\((.*?)\)<end_of_turn>\n<start_of_turn>model\n$', row['prompt'], re.S)
            if not match:
                raise ValueError('missing context in old prompt')
            row['context'] = match.group(1)
        row['prompt'] = prefix + f"<start_of_turn>user\n{row['text']}\n({row['context']})<end_of_turn>\n<start_of_turn>model\n"
        row['completion'] = base.target(row['tool'], row['args'], schemas[row['tool']][0]) if row['tool'] else 'chat<end_of_turn>'
        return row

    counts = Counter()
    seen = set()
    train = []
    for source, rows in (('round1', r1_train), ('old', old_train)):
        for row in rows:
            reason = None
            key = base.holdout_key(row['text'])
            if row.get('call_only_training') is False:
                reason = 'router_boundary'
            elif is_fixture(row['text'], fixture_keys):
                reason = 'fixture_text'
            elif key in held_keys:
                reason = 'heldout_text'
            elif source == 'old' and family_key(row['text'], row['tool'], row['args']) in held_families:
                reason = 'heldout_family'
            elif key in seen:
                reason = 'duplicate_text'
            if reason:
                counts[source + '_excluded_' + reason] += 1
                continue
            row = render(row)
            row['train2_source'] = source
            train.append(row)
            seen.add(key)
            counts[source + '_included'] += 1
    valid = [render(row) for row in dev if row.get('call_only_training') is not False
             and not is_fixture(row['text'], fixture_keys)]
    random.Random(2).shuffle(train)
    args.out.mkdir(parents=True)
    for name, rows in (('train', train), ('valid', valid), ('test_cases', test)):
        with (args.out / (name + '.jsonl')).open('x') as out:
            for row in rows:
                if name == 'test_cases':
                    row = {key: row[key] for key in ('text', 'tool', 'args', 'context', 'slice', 'family')}
                out.write(json.dumps(row, ensure_ascii=False) + '\n')
    manifest = {'counts': dict(counts), 'train': len(train), 'valid': len(valid), 'test': len(test),
                'seed': 2, 'validation': 'R1 dev, excluding router-boundary and fixture rows; old valid held out',
                'source_sha256': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_paths + fixtures},
                'limitations': ['Exact/normalized fixture exclusion does not remove all semantic equivalents.',
                                'Previously trained v2 may already have seen old rows matching R1 heldout families.',
                                'R1 test includes router-boundary cases excluded from call-only training.']}
    (args.out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
