# D-042 offline STT experiment

Kaggle authenticates with the installed modern access token. No phone recording is an upload input.
Keep all generated data under ignored `training/data/stt/`; keep phone data/results
under `/private/tmp/jimi-stt/`. Scripts deliberately do not modify the Android app.

Candidate: OpenAI Whisper `tiny.en`, full parameter adaptation, 5 smoke / 2,000 real
optimizer steps, LR 1e-5, effective batch 16, seed 42. Both T4s are used when available.
The app currently uses Kroko streaming zipformer en 2025-08-06 fp32. sttacc found
Whisper base.en most accurate against machine references but too slow; tiny.en is
the smaller experiment, not a proven improvement. Its second-pass latency still
needs real G88 measurement. [Official sherpa exporter](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html)
provides the supported encoder/decoder int8 ONNX format (not a transducer replacement).

## Resume setup

1. The recent Kaggle CLI automatically reads `~/.kaggle/access_token` (modern format)
   or `~/.kaggle/kaggle.json` (legacy). Never paste credentials
   into a notebook, command argument, chat, or this repository. Use your public
   Kaggle username as `OWNER` below. CLI is installed in `/private/tmp/jimi-sttft-venv`.
2. Use Python 3.11 for audio preparation (`sherpa-onnx==1.13.8 soundfile scipy numpy`).
   The Kaggle script installs its own dependencies; it refuses local training.
3. Acquire the public corpora below into `training/data/stt/public/`. Every source
   needs `manifest.csv` with `audio,text,speaker,license,url` (plus `accent` for CV).
   Audio paths are relative to that source folder; use real speaker IDs, not clip IDs.
   Keep exact release/model attribution alongside original downloads, outside upload folders.

| Folder | Source and preparation |
|---|---|
| `common_voice` | This run uses [Mozilla's public Kaggle release](https://www.kaggle.com/datasets/mozillaorg/common-voice), CC0, with 4,490 Indian-tagged training clips before filtering. `public_subset.py` filters explicit South Asian tags; unknown accents are excluded. Newer releases can instead be obtained through Mozilla Data Collective subject to their release terms. |
| `svarah` | This run uses [the public raw Svarah mirror](https://www.kaggle.com/datasets/kushal1506/svarah), retaining the [upstream AI4Bharat CC BY 4.0 license](https://huggingface.co/datasets/ai4bharat/Svarah), not the mirror's MIT label. It is published as a benchmark; our adaptation split must never be reported as an official Svarah test score. |
| `nptel` | Selected the public [NPTEL2020 Indian-English dataset](https://github.com/AI4Bharat/NPTEL2020-Indian-English-Speech-Dataset), beginning with its linked 1,000-clip Pure Set. Extract here, then `python training/stt_kaggle/import_public.py nptel --license CC-BY --url RELEASE_URL`. The importer supports corrected Pure Set transcripts and LibriSpeech format. Pure Set groups use lecturer/course descriptions; 25 clips lacking them are excluded. These are approximate speaker groups and need review before claiming speaker-disjoint evaluation. |
| `musan` | Extract WAV noise from [OpenSLR MUSAN](https://www.openslr.org/17/). |
| `demand` | Extract noise WAVs from [DEMAND](https://zenodo.org/records/1227121). |
| `synthetic` | Generate using the commands below. Only checked-in routing cases and clean RuleMatcher examples are read. Placeholders become the fictional name Alex. Phone transcripts are never used. |

Generate full synthetic commands on Kaggle or sequentially locally; use `--limit 2`
for a small local probe. Existing cached voice packs are read-only:

```sh
python training/stt_kaggle/synthesize.py --engine piper --pack /private/tmp/jimi-voices/piper-alan --data-dir /private/tmp/jimi-voices/vits-piper-en_US-ryan-medium/espeak-ng-data
python training/stt_kaggle/synthesize.py --engine supertonic --pack /private/tmp/jimi-voices/sherpa-onnx-supertonic-3-tts-int8-2026-05-11 --speaker 0
```

For each corpus, preparation caps clean audio at five hours, resamples to mono
16 kHz, excludes clips outside 0.3–25 seconds, splits 90/10 by speaker (by sentence
for synthetic), deduplicates audio, and adds one noisy training copy at 5–20 dB SNR.
Both MUSAN and DEMAND must be present. Development audio is clean and unaugmented.
The local phone WAV hashes are excluded before any mixing. Never manually put phone
audio in `public/`; the hash check cannot identify cropped/re-encoded derivatives.

The actual adaptation run uses `--hours-per-source 2` to fit local disk constraints.
The public Common Voice/Svarah snapshots lack stable speaker IDs. Their groups use
demographic tuples; NPTEL uses lecturer/course descriptions. These are approximate
groups, not proof of speaker independence. The private phone set is fully independent.

To reproduce public extraction, copy `public_subset.py` and
`public-subset-metadata.json` (renamed `kernel-metadata.json`, with your username)
into a fresh ignored staging directory and push it with the Kaggle CLI. It is a
private CPU job. Download `public-subsets.tar.gz` and extract its `common_voice/`
and `svarah/` directories under `training/data/stt/public/` with a safe tar extractor.
No credential or phone data is needed in this job.

## Smoke, real run, output

Run from this worktree with the audio-preparation environment. Replace `OWNER` with
the public Kaggle username. Fresh output directories are required; never erase
existing runs. Submission validates the upload file allowlist and uploads only the
prepared folder. Dataset and kernel are private. No token contents are read by our code.

```sh
python training/stt_kaggle/prepare.py --owner OWNER --mode smoke
python training/stt_kaggle/submit.py --owner OWNER --mode smoke
python training/stt_kaggle/prepare.py --owner OWNER --mode real
python training/stt_kaggle/submit.py --owner OWNER --mode real
```

For the existing private dataset, use these full-mixture run commands instead:

```sh
python training/stt_kaggle/prepare.py --owner OWNER --mode smoke --run-id full-smoke --hours-per-source 2
python training/stt_kaggle/submit.py --owner OWNER --mode smoke --run-id full-smoke --dataset-version
python training/stt_kaggle/prepare.py --owner OWNER --mode real --run-id full-real --reuse-prepared full-smoke
python training/stt_kaggle/submit.py --owner OWNER --mode real --run-id full-real --smoke-run-id full-smoke --reuse-dataset
```

Prepared WAVs/manifests are immutable hard links between those two runs. Uploads
contain one allowlisted archive; the kernel safely extracts it in temporary storage.
The real run reuses that cloud dataset after the matching smoke succeeds. Its
submitted code explicitly selects real mode and checks the exact manifest hash.

The submitter requests `NvidiaTeslaT4` (T4 ×2), polls `kaggle kernels status` every
30 seconds, and downloads output. Check the Kaggle GPU quota first. A real run
requires downloaded smoke `success.json` with successful int8 decoding and the
same training manifest hash. A failed kernel stops the workflow; inspect its logs
before retrying. Dataset processing delay/API errors may require resuming manually:

For a diagnostic synthetic-only smoke, add `--synthetic-smoke --run-id bootstrap2`
to preparation and `--run-id bootstrap2` to submission. This is not the full-data
smoke gate. Later runs use fresh `--run-id` values, `--dataset-version` for the existing
private dataset, and `--smoke-run-id NAME` to select the matching successful smoke.

```sh
/private/tmp/jimi-sttft-venv/bin/kaggle kernels status OWNER/jimi-stt-smoke
/private/tmp/jimi-sttft-venv/bin/kaggle kernels output OWNER/jimi-stt-smoke -p training/data/stt/outputs-smoke
```

The kernel exports using sherpa-onnx v1.13.8's exporter, substituting the fine-tuned
checkpoint loader. It records exporter and training-manifest hashes, loss, GPU count
and a public-audio decoding check. Select `real/tiny.en-encoder.int8.onnx`,
`real/tiny.en-decoder.int8.onnx`, and `real/tiny.en-tokens.txt`; leave all outputs ignored.
Export/API compatibility passed the five-step, full-mixture smoke on two T4 GPUs.
Training loss is not an accuracy claim.

## Local held-out gate

All 146 phone clips stay held out from training and hyperparameter selection.
The 20 old sttacc transcripts are Whisper pseudo-labels, not ground truth. Have the
user correct literal spoken text (including audible wake words) into a private JSON
mapping WAV basename to transcript. Label all clips if possible, at least 20. Do not
use saved Kroko `.txt` hypotheses as references. Freeze labels before comparison.

Use the existing local evaluator, in separate processes, with new result names:

```sh
/private/tmp/jimi-stt/venv/bin/python training/stt_eval/command_accuracy.py --count 146 --reference-count 146 --kind zipformer-streaming --model-dir BASELINE_KROKO_DIRECTORY --name sttft-kroko
/private/tmp/jimi-stt/venv/bin/python training/stt_eval/command_accuracy.py --count 146 --reference-count 146 --kind whisper --model-dir training/data/stt/outputs-full-real/real --name sttft-tiny
/private/tmp/jimi-stt/venv/bin/python training/stt_kaggle/compare.py --baseline /private/tmp/jimi-stt/sttft-kroko.json --candidate /private/tmp/jimi-stt/sttft-tiny.json --human-references /private/tmp/jimi-stt/human-references.json
```

`compare.py` ignores evaluator pseudo-reference scores and recomputes both WERs on
the same corrected labels. Adopt only with strictly lower WER, acceptable latency
(target <1.5 seconds for a 3-second command), and memory measured on the phone with
the assistant running. The app would need a Whisper second-pass adapter; the current
Moonshine adapter and Kroko streaming transducer cannot load these files unchanged.
