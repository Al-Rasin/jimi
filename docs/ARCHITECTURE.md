# Architecture

## 1. Big picture
```
                 ┌──────────── JarvisCoreService (foreground, always on, root-protected) ────────────┐
 Mic ──► Wake word ──► VAD ──► STT (streaming) ──► Router ──► Tool executor ──► Result
  ▲      "Hey Jimi"                                 │  ▲            │              │
  │                                                 ▼  │            ▼              ▼
  │                                   Brain levels:    │      Security gate   Reply builder
  │                                   1 Rule matcher   │      (risk + voice ID)    │
  │                                   2 FunctionGemma  │                           ▼
  │                                   3 Chat model ────┘                  TTS (voice) + Text
  │                                   4 Cloud (optional)                  (chat UI, lock overlay,
  │                                                                        notification, history)
  └── Proactive engine (battery, time, notifications, routines) can also start a reply
```

## 2. Gradle modules
| Module | Job | Depends on |
|---|---|---|
| `:app` | Compose UI (chat, history, notifications log, notes, settings, debug), Hilt setup | all |
| `:core` | Shared models: `Tool`, `ToolCall`, `ToolResult`, `Risk`, `Reply`, `JarvisLog`, utils | — |
| `:data` | Room DB + repositories (history, notes, reminders, notifications, memory facts), DataStore settings, encrypted secrets | `:core` |
| `:voice` | sherpa-onnx wrappers: `WakeWordDetector`, `Vad`, `SpeechToText`, `TextToSpeech`, `SpeakerVerifier`; audio focus | `:core` |
| `:llm` | llama.cpp JNI (`LlamaEngine`), model loading/unloading, streaming tokens, GBNF grammar for JSON | `:core` |
| `:brain` | `Router`, `RuleMatcher`, `ToolCaller` (FunctionGemma), `ChatResponder`, prompts, persona, memory retrieval, cloud providers | `:core`, `:llm`, `:data`, `:knowledge` |
| `:actions` | Tool implementations: `RootShell` (libsu), `IntentActions`, `JarvisAccessibilityService`, `SystemActions`, `ScreenReader` | `:core`, `:data` |
| `:notify` | `JarvisNotificationListener`, `NotificationClassifier`, triage rules, auto-clear + log | `:core`, `:data`, `:llm` |
| `:knowledge` | `WebSearch`, `PageReader` (jsoup), `WikiOffline` (Kiwix ZIM), `Embedder` + chunk ranking (RAG) | `:core` |
| `:service` | `JarvisCoreService`, boot receiver, proactive engine, routines engine, lock-screen overlay | most modules |
| `training/` (not Gradle) | Python: dataset tools, fine-tune notebooks, eval, export GGUF | — |

If a module feels too heavy early, it is OK to start inside `:app` with the same package names
and split later — but packages must match the module names above.

## 3. Request flow (one command)
1. `WakeWordDetector` fires → play short chime → show listening overlay (works on lock screen).
2. `Vad` + `SpeechToText` stream text. Stop on silence (~700 ms).
3. `Router` decides:
   - **RuleMatcher** hit (regex/keywords, e.g. "set alarm 7 am") → ToolCall directly (target < 100 ms).
   - Else **ToolCaller** (FunctionGemma, grammar-forced JSON) → ToolCall or `{"tool":"chat"}`.
   - `chat` → **ChatResponder** (chat model, with memory + optional web/wiki context).
   - If cloud enabled and local confidence low or user says "think hard" → cloud provider.
4. `SecurityGate` checks `Risk` + lock state + voice ID.
5. `ToolExecutor` runs the tool → `ToolResult`.
6. `ReplyBuilder` makes short text → TTS speaks it (sentence by sentence while LLM streams)
   AND text goes to chat UI + lock overlay + history DB.

### As built (2026-10-22)
One `:app` module, packages named like the modules above. Class map:
- `service/JarvisCoreService` — mic loop: wake (`voice/WakeWordDetector`) → `voice/SpeechToText` session (1 s pre-roll,
  own end-of-speech D-027) → `brain/Assistant.handle()` → voice (`voice/TextToSpeech`, queue per sentence) + panel
  (`ui/AssistantActivity`) + quiet notification. Follow-up listening after a reply (D-028). "One moment" filler after 1.5 s.
  Voice ID score from the command audio when locked (`voice/SpeakerVerifier`, D-034). Watchdog alarm in `BootReceiver`.
- `brain/Assistant` = Router: pending yes/no → panic / small talk → `RuleMatcher` → `ModelBrain.toolCall`
  (FunctionGemma, `llm/FunctionGemma` prompt + GBNF, D-030) → `CloudBrain` (optional, D-035) → `ModelBrain.chat` (Qwen3) → "can't".
  Every turn → `conversation_turn`; every tool → `tool_call_log`.
- `actions/ToolExecutor` = SecurityGate (CONFIRM → yes/no; while locked CONFIRM becomes VOICE_ID; lockdown after panic).
- `llm/Llama` — JNI to llama.cpp (`app/src/main/cpp/jarvis_llm.cpp`), prefix KV cache, grammar, streaming.
- `training/toolcall/` — dataset, MLX fine-tune, eval for the tool caller (D-033). `training/wakeword/` — wake word.

## 4. Tool contract (shared by rules, LLM, cloud, training data)
```kotlin
enum class Risk { SAFE, CONFIRM, VOICE_ID }   // VOICE_ID = needs speaker verification when locked

interface Tool {
    val name: String               // snake_case, stable — used in training data
    val description: String        // one line, used in prompts
    val argsSchema: JsonSchema     // used for GBNF grammar + validation
    val risk: Risk
    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
}
```
LLM output format (and training-data format):
```json
{"tool": "set_alarm", "args": {"time": "07:00", "label": "gym"}}
```
Multi-step: `{"tool": "plan", "args": {"steps": [ {...}, {...} ]}}`

### Tool list v1 (names are FINAL once training starts — do not rename later)
| Group | Tools |
|---|---|
| Talk | `chat`, `cancel`, `repeat_last` |
| Apps | `open_app`, `close_app`, `open_url` |
| Time | `set_alarm`, `set_timer`, `cancel_alarm` |
| Notes | `create_note`, `read_notes`, `create_reminder`, `list_reminders`, `delete_reminder` |
| Comms | `call`, `send_sms`, `send_whatsapp`, `read_messages`, `compose_email` |
| Media | `play_media`, `media_control`, `set_volume` |
| System | `toggle` (wifi, bluetooth, data, flashlight, dnd, airplane, location, hotspot, battery_saver), `set_brightness`, `device_status`, `take_screenshot`, `lock_phone`, `unlock_phone` |
| Screen | `read_screen`, `tap`, `type_text`, `scroll`, `go_back`, `go_home`, `open_recents` |
| Notifications | `read_notifications`, `clear_notifications`, `notification_summary` |
| Routines | `run_routine` (good_night, good_morning, custom) |
| Knowledge | `web_search`, `read_page`, `wiki_lookup`, `weather`, `news` |
| Memory | `remember_fact`, `forget_fact` |

Risk examples: `chat` SAFE, `set_alarm` SAFE, `send_whatsapp` CONFIRM, `call` CONFIRM,
`unlock_phone` VOICE_ID, any tool while locked that sends/deletes/pays → VOICE_ID.

## 5. Memory budget (4 GB phone — must respect)
| Part | RAM (approx) | When loaded |
|---|---|---|
| Wake word + VAD | < 30 MB | always |
| STT (streaming small) | 150–250 MB | always (warm) |
| TTS (Piper/Kokoro) | 60–300 MB | always (warm), pick by benchmark |
| FunctionGemma 270M Q8/Q4 | 200–550 MB | always (warm) |
| Chat model 0.6B–1B Q4 | 500–900 MB | on demand, unload after idle 5 min |
| Embedder (RAG) | 30–100 MB | on demand |
Total target while idle: **< 1.2 GB**. Real numbers are in BENCHMARKS.md.

## 6. Data (Room tables)
`conversation_turn`, `notification_log` (+ label: kept/cleared/auto), `note`, `reminder`,
`memory_fact`, `routine`, `routine_step`, `tool_call_log` (for retraining data), `wake_event` (wake-word feedback, D-023), `settings` (DataStore).
Secrets (API keys, PIN) → EncryptedSharedPreferences / Android Keystore only.

## 7. Lock screen & always-on
- Foreground service with persistent notification; `START_STICKY`; boot receiver.
- Root on start: add to `deviceidle whitelist`, set MIUI autostart/no-restrictions, `appops` for background mic.
- Overlay on lock screen: Activity with `setShowWhenLocked(true)` + `setTurnScreenOn(true)`.
- Unlock: root `input` PIN entry (PIN from Keystore) only after voice ID passes.

## 8. Training data loop
`tool_call_log` stores (text heard → tool call → user corrected? → result). Export to
`training/data/*.jsonl` for retraining FunctionGemma. Notification labels feed the notification classifier.
Wake word (D-023): each wake → `wake_event` row (time, score, clip file, verdict ✓/✗/missed/none) + WAV in
app files `wake/`. `training/wakeword/pull_feedback.sh` copies them to `models/wakeword/feedback/` for retraining.
