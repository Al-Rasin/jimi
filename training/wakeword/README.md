# Wake word training — "Hey Jimi" (D-020, D-021)

Custom openWakeWord model, trained on the Mac (CPU, no GPU needed). Output: `models/wakeword/out/hey_jimi.onnx`.
All data lives in `models/wakeword/` (git-ignored).

## 1. Python env (Python 3.11)
```bash
uv venv -p 3.11 .venv-ww && uv pip install -p .venv-ww/bin/python \
  openwakeword onnxruntime piper-tts 'torch==2.5.1' 'torchaudio==2.5.1' 'numpy<2' 'scipy==1.14.1' \
  'speechbrain==0.5.16' audiomentations torch-audiomentations torchinfo torchmetrics pronouncing \
  mutagen acoustics datasets pyyaml tqdm 'pyarrow<20' soundfile
```
Pins matter: newer scipy breaks `acoustics`, newer torchaudio needs torchcodec, pyarrow 20+ needs numpy 2.

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
