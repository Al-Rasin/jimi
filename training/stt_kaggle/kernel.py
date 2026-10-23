"""Kaggle-only Whisper adaptation with an explicit submitted run mode."""
import json
import os
from pathlib import Path
import subprocess
import sys

EXPECTED_MANIFEST_SHA256 = None
EXPECTED_MODE = None


def main():
    if not Path('/kaggle/input').is_dir():
        raise SystemExit('Training is restricted to Kaggle; do not train on the Mac.')
    subprocess.run([sys.executable, '-m', 'pip', 'install', '-q',
                    'openai-whisper==20250625', 'onnx==1.18.0',
                    'onnxruntime==1.23.2', 'sherpa-onnx==1.13.8', 'soundfile',
                    '--only-binary=onnx,onnxruntime'], check=True)
    import dataclasses
    import hashlib
    import importlib.util
    import random
    import tarfile
    import urllib.request
    import numpy as np
    import soundfile as sf
    import torch
    import whisper
    from torch.utils.data import DataLoader, Dataset

    torch.set_num_threads(2)
    torch.manual_seed(42)
    random.seed(42)
    np.random.seed(42)
    if not torch.cuda.is_available():
        raise RuntimeError('GPU required')
    roots = list(Path('/kaggle/input').rglob('train.jsonl'))
    archives = list(Path('/kaggle/input').rglob('stt-public.tar.gz'))
    if archives:
        if len(archives) != 1:
            raise RuntimeError('Attach exactly one public dataset representation')
        extracted = Path('/kaggle/temp/stt-public')
        extracted.mkdir(parents=True, exist_ok=False)
        with tarfile.open(archives[0]) as archive:
            archive.extractall(extracted, filter='data')
        roots = [extracted / 'train.jsonl']
    if len(roots) != 1:
        raise RuntimeError('Attach exactly one prepared public dataset')
    root = roots[0].parent
    mode = EXPECTED_MODE or json.loads((root / 'run_mode.json').read_text())['mode']
    manifest_hash = hashlib.sha256((root / 'train.jsonl').read_bytes()).hexdigest()
    if EXPECTED_MANIFEST_SHA256 is not None and manifest_hash != EXPECTED_MANIFEST_SHA256:
        raise ValueError('Attached dataset does not match the submitted training manifest')
    if mode not in ('smoke', 'real'):
        raise ValueError(mode)
    steps = 5 if mode == 'smoke' else 2000
    out = Path('/kaggle/working') / mode
    out.mkdir(exist_ok=False)
    model = whisper.load_model('tiny.en', device='cuda')
    tokenizer = whisper.tokenizer.get_tokenizer(False, task='transcribe')

    class AudioRows(Dataset):
        def __init__(self, split):
            self.rows = [json.loads(x) for x in (root / f'{split}.jsonl').read_text().splitlines()]
            self.cache = {}
            if not self.rows:
                raise ValueError(f'Empty {split}')

        def __len__(self):
            return len(self.rows)

        def __getitem__(self, index):
            if index in self.cache:
                return self.cache[index]
            row = self.rows[index]
            path = (root / row['audio']).resolve()
            if not path.is_relative_to(root.resolve()) or path.suffix != '.wav':
                raise ValueError('Unsafe audio path')
            audio, rate = sf.read(path, dtype='float32')
            if rate != 16000 or audio.ndim != 1 or not 0 < len(audio) <= 480000:
                raise ValueError('Expected mono 16 kHz, <=30 seconds')
            tokens = list(tokenizer.sot_sequence_including_notimestamps) + tokenizer.encode(' ' + row['text'].strip()) + [tokenizer.eot]
            if len(tokens) > model.dims.n_text_ctx:
                raise ValueError('Transcript too long')
            mel = whisper.log_mel_spectrogram(whisper.pad_or_trim(audio))
            result = mel, torch.tensor(tokens[:-1]), torch.tensor(tokens[1:])
            if len(self.cache) < 8192:
                self.cache[index] = result
            return result

    def collate(rows):
        mel, tokens, labels = zip(*rows)
        pad = torch.nn.utils.rnn.pad_sequence
        return torch.stack(mel), pad(tokens, batch_first=True, padding_value=tokenizer.eot), pad(labels, batch_first=True, padding_value=-100)

    train = DataLoader(AudioRows('train'), batch_size=16, shuffle=True, collate_fn=collate, num_workers=0)
    valid = DataLoader(AudioRows('dev'), batch_size=4, collate_fn=collate, num_workers=0)
    # Both T4s are used when available. The original model owns the checkpoint.
    network = torch.nn.DataParallel(model) if torch.cuda.device_count() > 1 else model
    optimizer = torch.optim.AdamW(model.parameters(), lr=1e-5, weight_decay=0.01)
    scaler = torch.amp.GradScaler('cuda')
    iterator = iter(train)
    losses = []
    network.train()
    for step in range(steps):
        optimizer.zero_grad(set_to_none=True)
        total = 0.0
        for _ in range(1):
            try:
                batch = next(iterator)
            except StopIteration:
                iterator = iter(train)
                batch = next(iterator)
            mel, tokens, labels = [x.cuda() for x in batch]
            with torch.autocast('cuda', dtype=torch.float16):
                logits = network(mel, tokens)
                loss = torch.nn.functional.cross_entropy(logits.flatten(0, 1), labels.flatten(), ignore_index=-100)
            if not torch.isfinite(loss):
                raise RuntimeError('Non-finite loss')
            scaler.scale(loss).backward()
            total += loss.item()
        scaler.unscale_(optimizer)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(optimizer)
        scaler.update()
        losses.append(total)
        if step % 100 == 0:
            print(json.dumps({'step': step, 'loss': total}), flush=True)
    network.eval()
    dev_loss = []
    with torch.no_grad():
        for i, batch in enumerate(valid):
            if mode == 'smoke' and i == 2:
                break
            mel, tokens, labels = [x.cuda() for x in batch]
            with torch.autocast('cuda', dtype=torch.float16):
                logits = network(mel, tokens)
                dev_loss.append(torch.nn.functional.cross_entropy(logits.flatten(0, 1), labels.flatten(), ignore_index=-100).item())
    checkpoint = out / 'checkpoint.pt'
    torch.save({'dims': dataclasses.asdict(model.dims), 'model_state_dict': model.cpu().state_dict()}, checkpoint)
    del network, model, optimizer
    torch.cuda.empty_cache()
    # Use the official sherpa exporter, overriding its model loader only.
    exporter = out / 'export-onnx.py'
    url = 'https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/scripts/whisper/export-onnx.py'
    urllib.request.urlretrieve(url, exporter)
    spec = importlib.util.spec_from_file_location('sherpa_export', exporter)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.load_model = lambda name: whisper.load_model(str(checkpoint), device='cpu')
    original_export = torch.onnx.export
    def legacy_export(*args, **kwargs):
        kwargs['dynamo'] = False
        return original_export(*args, **kwargs)
    torch.onnx.export = legacy_export
    os.chdir(out)
    sys.argv = ['export-onnx.py', '--model', 'tiny.en']
    from whisper.model import disable_sdpa
    with disable_sdpa():
        module.main()
    import sherpa_onnx
    recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(out / 'tiny.en-encoder.int8.onnx'),
        decoder=str(out / 'tiny.en-decoder.int8.onnx'),
        tokens=str(out / 'tiny.en-tokens.txt'), num_threads=2, language='en', task='transcribe')
    row = json.loads((root / 'dev.jsonl').read_text().splitlines()[0])
    audio, rate = sf.read(root / row['audio'], dtype='float32')
    stream = recognizer.create_stream()
    stream.accept_waveform(rate, audio)
    recognizer.decode_stream(stream)
    if not stream.result.text.strip():
        raise RuntimeError('Export decoded blank public sample; investigate before real run')
    report = {'mode': mode, 'steps': steps, 'gpus': torch.cuda.device_count(),
              'train_batch_size': 16, 'feature_cache_limit': 8192,
              'train_loss': losses[-1], 'dev_loss': sum(dev_loss) / len(dev_loss),
              'export_decode_ok': True, 'torch': torch.__version__,
              'exporter_sha256': hashlib.sha256(exporter.read_bytes()).hexdigest(),
              'manifest_sha256': hashlib.sha256((root / 'train.jsonl').read_bytes()).hexdigest()}
    (out / 'success.json').write_text(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
