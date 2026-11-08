# Jimi

A voice assistant for my own Android phone. It runs offline, works when the
phone is locked, and can control almost anything on the phone because the phone
is rooted.

Say "Hey Jimi", ask for something, and it answers with voice and text.

I built it for one phone: a Redmi 10 Prime (4 GB RAM, Helio G88, MIUI, rooted with
Magisk). Everything is tuned for that phone, so it should run fine on most
mid-range phones too, but I have only tested it on mine.

## What it can do

- Wake word, voice activity detection, speech to text and text to speech, all on
  the phone (sherpa-onnx)
- Phone control: alarms, calls, messages, WhatsApp/Messenger, apps, settings,
  volume, flashlight, Wi-Fi and more, using root, intents and accessibility
- Small local models through llama.cpp: a fine-tuned FunctionGemma 270M picks the
  action, Qwen3 0.6B handles normal chat
- Notification triage: reads, sorts and clears notifications, and keeps a log
- Notes, reminders, chat history and memory, stored with Room
- Works on the lock screen, with a PIN/voice check before risky actions
- Web search and page reading when there is internet
- Optional cloud chat (Groq, Gemini, OpenRouter, Cloudflare). Off by default;
  phone actions always stay on the phone

## How it works

```
Mic → wake word → VAD → speech to text → router → tool → reply (voice + text)
                                          │
                     rules → small tool model → chat model → cloud (optional)
```

Simple commands are handled by fast word rules. If the rules don't match, the
tool model picks an action, and the chat model handles anything else. Every
phone action is a "tool" with a risk level, so risky ones go through a
security check first.

More detail in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Speed and memory numbers from the
phone are in [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

## Stack

Kotlin, Jetpack Compose, Coroutines/Flow, Hilt, Room, DataStore, WorkManager,
libsu, sherpa-onnx, llama.cpp (JNI), OkHttp, jsoup. Training scripts in
`training/` use Python (Hugging Face, Unsloth, MLX).

## Building

Models are not in the repo, because they are too big.

```bash
tools/fetch_llama.sh     # llama.cpp source for the JNI build
tools/fetch_speech.sh    # speech-to-text and voice models
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The wake word model goes in `models/wakeword/app-assets/wakeword/` (see
`training/wakeword/README.md`). The GGUF models are pushed to the phone
separately.

Needs Android 11+ and root. This is a personal project, so it asks for a lot of
permissions. It is not meant for the Play Store.
