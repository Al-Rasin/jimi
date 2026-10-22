# Offline STT bench

Compare local sherpa-onnx models before changing Jimi's current Kroko model
(D-026). No audio is uploaded. Downloads and package installation need internet;
evaluation does not. Python 3.11 is recommended.

## Setup

From the repository root:

```sh
uv venv --python 3.11 .venv-stt
uv pip install --python .venv-stt/bin/python sherpa-onnx==1.13.8 numpy==2.4.6
source .venv-stt/bin/activate
```

Without uv, use `python3.11 -m venv .venv-stt`, activate it, then
`python -m pip install sherpa-onnx==1.13.8 numpy==2.4.6`.
The existing `.gitignore` covers `.venv*/`, `models/`, `training/data/`, and
`training/outputs/`. Keep recordings, references and generated CSVs there:
transcripts can contain private information too. Never commit model binaries.

## Models

Use the ONNX exports in the official
[sherpa-onnx ASR release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models),
not original PyTorch/Hugging Face weights. These download links were checked
against the release asset list. Archive sizes include bundled files, sometimes
both floating-point and int8 weights; they are not runtime RAM estimates.

| Model / archive | `--kind` | Approximate download | Purpose |
|---|---|---|---|
| [Kroko English 2025-08-06](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2) | `zipformer-streaming` | 57 MB | Current baseline, fp32 |
| [NeMo Parakeet TDT 0.6b v2 int8](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2) | `nemo-transducer` | 482 MB | Larger English accuracy candidate; test phone memory separately |
| [Zipformer GigaSpeech 2023-12-12](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-gigaspeech-2023-12-12.tar.bz2) | `zipformer-offline` | 311 MB | Offline English transducer |
| [Whisper tiny.en](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2) | `whisper` | 118 MB | Small English baseline, selects int8 |
| [Whisper base.en](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en.tar.bz2) | `whisper` | 209 MB | Next English size, selects int8 |
| [Whisper small.en](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.en.tar.bz2) | `whisper` | 636 MB | Larger accuracy candidate, selects int8 |
| [Moonshine tiny English int8](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2) | `moonshine` | 108 MB | Small offline model |

Example download (change `pack` to an archive stem from the table):

```sh
mkdir -p models/stt
pack=sherpa-onnx-whisper-tiny.en
curl -fL "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$pack.tar.bz2" -o "models/stt/$pack.tar.bz2"
tar -xjf "models/stt/$pack.tar.bz2" -C models/stt
```

Run models one at a time on the 8 GB Mac. Check each model's license before
redistributing it; Kroko's existing personal-use choice is recorded in D-026.
Other sherpa-supported formats are not automatically interchangeable: this
bench supports the six kinds in `--help`, including offline `paraformer`.
The Moonshine adapter uses the original four-component export linked above,
not Moonshine v2 or the newer two-component exports.

The script finds component files in the extracted directory (not recursively),
preferring `int8` when available. Ambiguous selections fail with instructions.
Use repeated `--file ROLE=filename` to choose particular files, for example
`--file encoder=tiny.en-encoder.onnx --file decoder=tiny.en-decoder.onnx`
for fp32 Whisper. Roles: `encoder`, `decoder`, `joiner`, `tokens` for transducers;
`encoder`, `decoder`, `tokens` for Whisper; `model`, `tokens` for Paraformer;
`preprocess`, `encode`, `uncached_decode`, `cached_decode`, `tokens` for Moonshine.

## Audio and references

Use **16 kHz, mono, signed 16-bit PCM WAV**. The bench rejects wrong formats,
empty files and truncated audio instead of silently resampling. It reads WAVs
directly inside `--wav-dir`, in sorted filename order. Use a separate folder
for each corpus. Human recordings with independently checked transcripts are
needed to rank models for Bangladeshi English.

For a synthetic smoke test on macOS:

```sh
mkdir -p training/data/stt_test/manual
say -v Samantha -o training/data/stt_test/manual/flashlight.aiff 'turn on flashlight'
afconvert -f WAVE -d LEI16@16000 -c 1 training/data/stt_test/manual/flashlight.aiff training/data/stt_test/manual/flashlight.wav
printf 'flashlight.wav\tturn on flashlight\n' > training/data/stt_test/manual/ref.tsv
```

Repeat with “Hey Jimi”, “Hey Jimi turn on flashlight”, “turn off flashlight”,
and other real commands. Synthetic voices do **not** represent the user's
accent. In a restricted session `say` may exit successfully yet write zero
frames; check `afinfo` for a nonzero duration or let the bench validate it.

Reference format: UTF-8 TSV, no header, one `filename.wav<TAB>correct text`
per line. Use a literal tab, not the two characters `\t`. Every evaluated WAV
must have exactly one reference; extra names and duplicates are rejected.
An empty text field is allowed for silence. Do not use a model's guesses as
ground truth. Keep “Jimi” as written: “Jimmy” remains an error unless a separate,
explicitly documented name-alias scoring policy is introduced.

## Run

```sh
python training/stt_eval/eval_stt.py \
  --wav-dir training/data/stt_test/manual \
  --model-dir models/stt/sherpa-onnx-whisper-tiny.en \
  --kind whisper --ref training/data/stt_test/manual/ref.tsv
```

Omit `--ref` for transcription and speed only. `--threads` defaults to 2.
`--csv training/outputs/stt_eval/my-run.csv` selects a new output file;
otherwise a unique timestamped CSV is created in `training/outputs/stt_eval/`.
Existing CSVs are never overwritten. Per-file rows include text, reference,
duration, decode seconds, RTF, word errors, WER, sentence match, selected model
files, load time, thread count and sherpa version. Console output also contains
corpus totals. CSV metric fractions run from 0 to 1 (WER can exceed 1).

Timing excludes WAV I/O and model loading, includes stream creation and feature
extraction, and follows one untimed warm-up using the first file. Streaming
receives 100 ms chunks without real-time sleeps, then 300 ms silence and
`input_finished()` to flush; flush processing counts in decode time, added
silence does not count in audio duration. This measures throughput, not live
endpoint delay or time to first token. No app wake stripping or hotwords apply.

RTF = decode seconds / original audio seconds; below 1 is faster than real time.
Corpus RTF uses summed times, not an average of file RTFs. WER uses total word
edit distance / total reference words. Scoring applies Unicode NFKC, case
folding, punctuation removal and whitespace splitting. Sentence accuracy is
exact equality after that normalization. Digits and spoken number words are
not equated. Empty-reference WER is blank; its insertions still contribute to
corpus errors. An all-empty-reference corpus has undefined WER.

Treat **4–6× Mac M1 decode time** as a rough Helio G88 planning estimate, not
a phone measurement; model architecture, memory, thermals and threading can
change the ratio. Verify candidates on the phone before replacing D-026.

## Recorded comparison

Noise experiment per-file CSVs and logs are under
`training/outputs/denoise/`. The run used labelled bundled speech because the
existing synthetic command WAVs were empty and phone transcripts were unverified.


## Noise and GTCRN denoising

Download `gtcrn_simple.onnx` from the official
[speech enhancement release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speech-enhancement-models)
into git-ignored `models/denoise/`. The Python API requires sherpa-onnx 1.13.8.

```sh
python training/stt_eval/eval_stt.py \
  --wav-dir training/data/stt_test/manual \
  --model-dir models/stt/kroko --kind zipformer-streaming \
  --ref training/data/stt_test/manual/ref.tsv \
  --noise-dir training/data/noise --snr 0 5 10 --seed 0 \
  --denoise-model models/denoise/gtcrn_simple.onnx \
  --csv training/outputs/stt_eval/denoise.csv
```

Noise WAVs must also be 16 kHz mono PCM16 and are selected nonrecursively.
`--noise-dir` defaults to SNRs 0/5/10 dB. An empty noise directory, or supplying
`--snr` without `--noise-dir`, generates white noise instead. A nonexistent
noise directory is an error, to catch typos. No generated audio is written.
To compare different noise types, run separately with one noise type per folder.

Each run includes a clean baseline, then noisy and (when a model is supplied)
noisy+denoised conditions at every requested SNR. Supplying only
`--denoise-model` compares clean versus clean+denoised. Both noisy conditions
use the identical mixture. A sorted corpus, sorted noise filenames, and
`--seed` (default 0) reproduce selection and offsets. Each speech file uses
one randomly chosen noise recording and circular offset, repeated if shorter
than the speech; that same noise segment is reused across SNRs.

SNR uses whole-utterance RMS, including silence, rather than active-speech
RMS. The mixture is jointly peak-normalized only when it exceeds full scale;
there is no clipping, and SNR is preserved. Silent speech or noise cannot
have a defined SNR and fails explicitly. No source WAVs are changed.

CSV adds condition, SNR, noise source, seed, model, denoise load/processing
seconds, denoise RTF, and combined STT+denoise RTF. Console corpus WER and RTF
are reported **separately for each condition/SNR**, never pooled. Denoiser
loading and one warm-up are excluded; denoise RTF uses original speech duration.
The complete utterance is enhanced before streaming STT, so this experiment
does not measure live microphone latency. Desktop noise results are a smoke
test; real user speech and phone timing are required before enabling this.
