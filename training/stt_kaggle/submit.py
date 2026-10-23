"""Upload only the prepared public directory; smoke must pass before real."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import time


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--owner', required=True)
    p.add_argument('--mode', choices=['smoke', 'real'], required=True)
    p.add_argument('--run-id')
    p.add_argument('--smoke-run-id', default='smoke')
    p.add_argument('--dataset-version', action='store_true')
    p.add_argument('--reuse-dataset', action='store_true')
    p.add_argument('--input-run-id')
    p.add_argument('--dataset-revision', type=int)
    args = p.parse_args()
    if args.dataset_revision is not None and args.dataset_revision < 1:
        p.error('dataset-revision must be positive')
    run_id = args.run_id or args.mode
    input_id = args.input_run_id or run_id
    if not all(x.replace('-', '').isalnum() for x in (run_id, args.smoke_run_id, input_id)):
        p.error('Run IDs must contain only letters, numbers and hyphens')
    if not any((Path.home() / '.kaggle' / name).is_file()
               for name in ('access_token', 'kaggle.json')):
        raise SystemExit('Missing Kaggle credentials in ~/.kaggle/')
    repo = Path(__file__).resolve().parents[2]
    data = repo / 'training/data/stt'
    upload = data / ('upload-' + input_id)
    if upload.is_symlink():
        raise ValueError('Upload directory must not be a symlink')
    rows = []
    for split in ('train', 'dev'):
        rows += [json.loads(x) for x in (upload / f'{split}.jsonl').read_text().splitlines()]
    expected = {'train.jsonl', 'dev.jsonl', 'run_mode.json', 'provenance.json', 'dataset-metadata.json'}
    expected.update(r['audio'] for r in rows)
    for name in expected:
        path = upload / name
        if not path.resolve().is_relative_to(upload.resolve()) or not path.is_file() or path.is_symlink():
            raise ValueError('Upload manifest path must identify a contained regular file')
    for path in upload.rglob('*'):
        if path.is_symlink():
            raise ValueError('No symlinks in upload tree')
        if path.is_file() and path.relative_to(upload).as_posix() not in expected:
            raise ValueError('Unexpected upload file')
    if any(r['source'] not in ('common_voice', 'svarah', 'nptel', 'synthetic') for r in rows):
        raise ValueError('Non-public source')
    if json.loads((upload / 'run_mode.json').read_text())['mode'] != args.mode:
        raise ValueError('Wrong prepared mode')
    metadata = json.loads((upload / 'dataset-metadata.json').read_text())
    if metadata['id'] != args.owner + '/jimi-stt-public':
        raise ValueError('Dataset owner mismatch')
    if args.mode == 'real':
        report = json.loads((data / ('outputs-' + args.smoke_run_id) / 'smoke/success.json').read_text())
        digest = hashlib.sha256((upload / 'train.jsonl').read_bytes()).hexdigest()
        if not report['export_decode_ok'] or report['manifest_sha256'] != digest:
            raise ValueError('Smoke must pass on exactly the same public training manifest')
    cli = '/private/tmp/jimi-sttft-venv/bin/kaggle'

    def run(*arguments):
        return subprocess.run([cli, *arguments], check=True, text=True, capture_output=True).stdout

    # CLI dataset create is PRIVATE by default; never pass --public.
    if not args.reuse_dataset:
        bundle = data / ('bundle-' + run_id)
        bundle.mkdir(exist_ok=False)
        shutil.copyfile(upload / 'dataset-metadata.json', bundle / 'dataset-metadata.json')
        with tarfile.open(bundle / 'stt-public.tar.gz', 'w:gz') as archive:
            for name in sorted(expected - {'dataset-metadata.json'}):
                archive.add(upload / name, arcname=name, recursive=False)
    if args.reuse_dataset:
        pass
    elif args.mode == 'smoke' and not args.dataset_version:
        print(run('datasets', 'create', '-p', str(bundle), '--dir-mode', 'zip'))
    else:
        print(run('datasets', 'version', '-p', str(bundle), '-m', run_id, '--dir-mode', 'zip'))
    for _ in range(40):
        try:
            dataset_status = run('datasets', 'status', metadata['id'])
        except subprocess.CalledProcessError:
            time.sleep(30)
            continue
        if 'ready' in dataset_status.lower():
            break
        if any(word in dataset_status.lower() for word in ('error', 'fail')):
            raise RuntimeError(dataset_status)
        time.sleep(30)
    else:
        raise TimeoutError('Dataset processing did not finish')
    kernel = data / ('kernel-' + run_id)
    kernel.mkdir(exist_ok=False)
    config = json.loads((Path(__file__).parent / 'kernel-metadata.json').read_text())
    config['id'] = args.owner + '/jimi-stt-' + run_id
    config['title'] = 'Jimi STT ' + run_id
    dataset_ref = metadata['id']
    if args.dataset_revision is not None:
        dataset_ref += '/' + str(args.dataset_revision)
    config['dataset_sources'] = [dataset_ref]
    (kernel / 'kernel-metadata.json').write_text(json.dumps(config, indent=2))
    code = Path(__file__).with_name('kernel.py').read_text()
    digest = hashlib.sha256((upload / 'train.jsonl').read_bytes()).hexdigest()
    code = code.replace('EXPECTED_MANIFEST_SHA256 = None', 'EXPECTED_MANIFEST_SHA256 = ' + repr(digest))
    code = code.replace('EXPECTED_MODE = None', 'EXPECTED_MODE = ' + repr(args.mode))
    (kernel / 'kernel.py').write_text(code)
    print(run('kernels', 'push', '-p', str(kernel), '--accelerator', 'NvidiaTeslaT4'))
    deadline = time.monotonic() + 12 * 3600
    while time.monotonic() < deadline:
        try:
            status = run('kernels', 'status', config['id'])
        except subprocess.CalledProcessError:
            print('Status request failed; retrying.', flush=True)
            time.sleep(30)
            continue
        print(status.strip(), flush=True)
        if 'complete' in status.lower():
            print(run('kernels', 'output', config['id'], '-p', str(data / ('outputs-' + run_id)),
                      '--file-pattern', r'.*(int8\.onnx|tokens\.txt|success\.json)$'))
            return
        if any(x in status.lower() for x in ('error', 'cancel', 'fail')):
            raise RuntimeError(status)
        time.sleep(30)
    raise TimeoutError('Polling ended; kernel may still be running. Inspect Kaggle status.')


if __name__ == '__main__':
    main()
