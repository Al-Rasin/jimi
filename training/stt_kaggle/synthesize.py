"""Generate only repository command examples, never phone transcripts."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--engine', choices=['piper', 'supertonic'], required=True)
    p.add_argument('--pack', type=Path, required=True)
    p.add_argument('--model', default='en_GB-alan-medium.onnx')
    p.add_argument('--data-dir', type=Path, help='Piper espeak-ng-data directory')
    p.add_argument('--speaker', type=int, default=0)
    p.add_argument('--limit', type=int, default=0)
    args = p.parse_args()
    import sherpa_onnx as s
    import soundfile as sf
    repo = Path(__file__).resolve().parents[2]
    commands = set()
    for line in (repo / 'tools/smoke_routing_cases.txt').read_text().splitlines():
        if line.lstrip().startswith('{'):
            text = json.loads(line)['text']
            text = re.sub(r'\{\{[^}]+\}\}', 'Alex', text)
            commands.add(text)
    # Exclude explicitly misheard phone examples: those are not intended speech.
    code = (repo / 'app/src/test/java/com/alrasin/jarvis/brain/RuleMatcherTest.kt').read_text()
    code = re.sub(r'@Test fun misheardCommands\(\).*?(?=@Test)', '', code, flags=re.S)
    commands.update(json.loads('"' + x + '"') for x in re.findall(r'\bm\("((?:[^"\\]|\\.)*)"\)', code))
    pack = args.pack
    def onnx(name):
        quantized = pack / (name + '.int8.onnx')
        return str(quantized if quantized.exists() else pack / (name + '.onnx'))
    if args.engine == 'piper':
        data_dir = args.data_dir or pack / 'espeak-ng-data'
        if not (data_dir / 'phontab').is_file():
            raise ValueError('Piper requires espeak-ng-data; supply --data-dir from the official voice pack')
        config = s.OfflineTtsModelConfig(vits=s.OfflineTtsVitsModelConfig(
            model=str(pack / args.model), tokens=str(pack / 'tokens.txt'),
            data_dir=str(data_dir)), num_threads=2)
    else:
        config = s.OfflineTtsModelConfig(supertonic=s.OfflineTtsSupertonicModelConfig(
            duration_predictor=onnx('duration_predictor'),
            text_encoder=onnx('text_encoder'),
            vector_estimator=onnx('vector_estimator'), vocoder=onnx('vocoder'),
            tts_json=str(pack / 'tts.json'), unicode_indexer=str(pack / 'unicode_indexer.bin'),
            voice_style=str(pack / 'voice.bin')), num_threads=2)
    tts = s.OfflineTts(s.OfflineTtsConfig(model=config))
    out = repo / 'training/data/stt/public/synthetic'
    out.mkdir(parents=True, exist_ok=True)
    manifest = out / 'manifest.csv'
    exists = manifest.exists()
    with manifest.open('a', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=['audio', 'text', 'speaker', 'license', 'url'])
        if not exists:
            writer.writeheader()
        for text in sorted(commands)[:args.limit or None]:
            if not text.strip():
                continue
            identity = f'{args.engine}:{args.model}:{args.speaker}:{text}'
            name = hashlib.sha256(identity.encode()).hexdigest() + '.wav'
            if (out / name).exists():
                continue
            config = s.GenerationConfig()
            config.sid = args.speaker
            config.num_steps = 5
            config.extra = {'lang': 'en'}
            generated = tts.generate(text, config)
            if generated.sample_rate <= 0 or not len(generated.samples):
                raise RuntimeError('TTS returned empty audio')
            sf.write(out / name, generated.samples, generated.sample_rate)
            writer.writerow({'audio': name, 'text': text, 'speaker': identity.split(':')[:3],
                             'license': 'synthetic; retain source voice model card',
                             'url': 'https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models'})
    print(f'Public synthetic audio: {out}')


if __name__ == '__main__':
    main()
