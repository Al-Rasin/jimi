# Wake word training — "Hey Jimi" (D-020, D-021)

Custom openWakeWord model, trained on the Mac (CPU, no GPU needed). Output: `models/wakeword/out/hey_jimi.onnx`.
All data lives in `models/wakeword/` (git-ignored).

## 1. Python env (Python 3.11)
```bash
uv venv -p 3.11 .venv-ww && uv pip install -p .venv-ww/bin/python \
  openwakeword onnxruntime piper-tts 'torch==2.5.1' 'torchaudio==2.5.1' 'numpy<2' 'scipy==1.14.1' \
  'speechbrain==0.5.16' audiomentations torch-audiomentations torchinfo torchmetrics pronouncing \
  mutagen acoustics datasets pyyaml tqdm 'pyarrow<20' soundfile 'onnx==1.16.2'
```
`onnx` is needed for the final export (without it a full training run is lost). Pins matter: newer scipy breaks `acoustics`, newer torchaudio needs torchcodec, pyarrow 20+ needs numpy 2.

## 2. Data (in `models/wakeword/`)
| What | Where | Source |
|---|---|---|
| Piper voices (904 + 24 L2-accent + 109 speakers) | `piper/*.onnx` | huggingface `rhasspy/piper-voices`: en_US-libritts_r-medium, en_US-l2arctic-medium, en_GB-vctk-medium |
| Negative speech features (first 800k rows ≈ 280 h) | `data/acav_part.raw` → `prepare_data.py acav` | `davidscripka/openwakeword_features` (HTTP range download, full file is 17 GB) |
| False-positive validation | `data/validation_set_features.npy` | same repo |
| Room echo (RIR) | `rirs/*.wav` | `davidscripka/MIT_environmental_impulse_responses` (16khz) |
| Background noise | `noise/*.parquet` → `prepare_data.py noise` | `agkphysics/AudioSet` bal_train/00.parquet |
| User's real voice | `user/pos/*.wav`, `user/neg/*.wav` | phone recordings → `prepare_data.py user ...` |

User recordings: MIUI Sound Recorder saves to
`/data/media/0/Android/data/com.android.soundrecorder/files/` (root). Convert:
`ffmpeg -i in.aac -ar 16000 -ac 1 out.wav`, then cut:
```bash
python prepare_data.py user out.wav ../../models/wakeword/user/pos --to 77        # "Hey Jimi" part
python prepare_data.py user out.wav ../../models/wakeword/user/neg --negative --from 78   # normal talk
```

## 3. Train
```bash
PY=.venv-ww/bin/python training/wakeword/train.sh all   # or: generate | user | augment | train
```
`generate_samples.py` replaces piper-sample-generator (Linux-only) with piper-tts.
Config: `hey_jimi.yml`. openWakeWord `train.py` is pinned to a commit inside `train.sh`.
This is the original generation/training workflow. For feedback retraining use the entry point below,
which uses `train_fast.py` (D-024); the old `train.sh train`/`all` auto-training output is not the chosen model.

### Synthetic examples from real STT mishears
Real transcripts of the user's "Hey Jimi" include `izumi`, `hayzumi`, `hazumi`, `zumi`,
`hey zooming`, and `hi jimmy`. These are pronunciation clues, not reliable wake labels.
For synthetic positives, generate "Hey Jimi" / "hey jimmy" with varied accents, speeds and
pronunciations; try those mishear spellings as TTS prompts only after listening confirms
the audio sounds like the intended wake phrase. An actual wake remains positive even when STT is wrong.
For synthetic hard negatives, generate genuine non-wake uses of similar sounds (for example,
"Izumi is here" or "keep zooming"), then listen to confirm they do not contain the wake phrase.
Do not put every mishear into `custom_negative_phrases`: that would teach the model to reject
the user's real pronunciation. Keep variants of the same source clip in the same dataset split.

## 4. Use in the app
Copy the 3 models the app needs into `models/wakeword/app-assets/wakeword/` (git-ignored, packed into the APK):
`melspectrogram.onnx`, `embedding_model.onnx` (from the openwakeword package `resources/models/`) and `hey_jimi.onnx`
(from `models/wakeword/out/`). If `hey_jimi.onnx` is missing the app falls back to `hey_jarvis_v0.1.onnx`.
New model without rebuilding the app: `adb push hey_jimi.onnx /sdcard/Android/data/com.alrasin.jarvis/files/models/wakeword/`
then Stop/Start in the app.

Check the Kotlin detector matches Python: put 16 kHz mono WAVs in `/sdcard/Android/data/com.alrasin.jarvis/files/test/`,
run `adb shell am start -n com.alrasin.jarvis/.ui.MainActivity --es test_dir <that dir>`, read `adb logcat -s Jarvis | grep TEST`.

## 5. Feedback loop (D-023)
Every voice wake asks "Was that you?" (✓/✗). Clips are saved on the phone with the answer in the file name.
After some days: `training/wakeword/pull_feedback.sh` → `models/wakeword/feedback/{yes,no,missed,near,none}/`.
Retrain (PLAN 3.7): `yes` + `missed` → extra positives, `no` → extra negatives (listen to `near`/`none` first).

From the repository root, prepare feedback without training:
```bash
python3 training/wakeword/retrain.py
python3 training/wakeword/retrain.py --skip-pull   # offline dry run; no phone or ML packages needed
```
The default command uses `pull_feedback.sh` when a phone is available and skips pulling otherwise.
It applies `label_fixes.json`, prints positive/negative and skipped counts, and writes a fresh
dataset under `models/wakeword/retrain/<run>/` (`pos/`, `neg/`, `manifest.json`). Existing phone
clips are kept unchanged. Use `--skip-pull --feedback-dir PATH` for another local feedback folder and
`--label-fixes PATH` for another correction file. Empty or missing feedback is safe.

The checked-in fixes mark wake events **43, 44, 47, 48, 50** positive: the user rejected the
STT text, not the wake. Since exported WAV names do not include database event IDs, each fix
also has its known timestamp prefix. `yes`, fixed events and `missed` are positive;
`no` is negative. Unreviewed `near` and `none` clips are skipped. After listening, add an exact
filename to the correction file's `clips` map with value `"no"` for a confirmed non-wake near
miss, or `"yes"` for a real wake. The `events` list records event ID, clip prefix and verdict.

Only when ready to spend CPU and memory, explicitly request training:
```bash
.venv-ww/bin/python training/wakeword/retrain.py --skip-pull --train
```
This requires the existing synthetic train/test WAVs, openWakeWord training dependencies,
augmentation assets and background features. It reuses the existing augmentation wrapper
and `train_fast.py` with D-024 settings (negative weight 5000, layer 64, 20,000 steps).
Feedback augments the training set; existing synthetic test clips remain separate. It writes
a candidate in the isolated run directory. Compare old and new models on the same held-out
real clips, then run PLAN 3.7 phone acceptance checks before manually swapping; training alone
does not establish that the candidate is better, and no model is deployed automatically.
