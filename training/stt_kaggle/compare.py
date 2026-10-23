"""Local-only WER gate over identical human-corrected phone references."""
import argparse
import importlib.util
import json
from pathlib import Path


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--baseline', type=Path, required=True)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--human-references', type=Path, required=True,
                   help='Private JSON mapping WAV basename to corrected literal transcript')
    args = p.parse_args()
    repo = Path(__file__).resolve().parents[2]
    for path in (args.baseline, args.candidate, args.human_references):
        if path.resolve().is_relative_to(repo):
            raise ValueError('Phone evaluation files must remain outside the repo')
    spec = importlib.util.spec_from_file_location('bench', repo / 'training/stt_eval/eval_stt.py')
    bench = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bench)
    references = json.loads(args.human_references.read_text())
    baseline = json.loads(args.baseline.read_text())
    candidate = json.loads(args.candidate.read_text())
    scores = []
    words = sum(len(bench.normalize(t)) for t in references.values())
    if len(references) < 20 or not words:
        raise ValueError('Need at least 20 corrected held-out clips with reference words')
    for result in (baseline, candidate):
        rows = {r['file']: r for r in result['rows']}
        if not references.keys() <= rows.keys():
            raise ValueError('Both runs must decode every corrected reference clip')
        errors = sum(bench.distance(bench.normalize(text), bench.normalize(rows[name]['hypothesis']))
                     for name, text in references.items())
        scores.append(errors / words)
    print(json.dumps({'clips': len(references), 'words': words,
                      'baseline_wer': scores[0], 'candidate_wer': scores[1],
                      'accuracy_gate_pass': scores[1] < scores[0],
                      'adopted': False, 'remaining_gate': 'real-phone latency and memory'}, indent=2))


if __name__ == '__main__':
    main()
