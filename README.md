# SQL AI (Jarvis-AI)

**24/7 always-on, full phone-control AI assistant for Android.**

Say **"SQL, open WhatsApp and message Ali"** — SQL AI reads the screen through the
Accessibility service, plans the steps with a free LLM provider, and performs the
taps, swipes and typing for you.

| | |
|---|---|
| Repository | `Jarvis-AI` |
| App name | **SQL AI** |
| Package | `com.sqlai.assistant` |
| Language | Kotlin + Jetpack Compose |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14), compiled against 35 |
| License | MIT |

---

## What's new in v1.2

- **Gemini Live Audio (native voice)** - `GeminiLiveAudioEngine.kt` streams the
  assistant's replies through Gemini's Live API (bidi WebSocket + protobuf):
  human-like male/female voices, routed to the phone speaker or Bluetooth
  headset, with automatic fallback to Android TTS. Full-duplex mic streaming
  runs live on phone and WhatsApp calls.
- **Unlimited agentic loop** - `AgenticLoopEngine.kt` has NO step budget: it
  keeps Observe -> Think -> Act -> Verify running until the task is 100%
  verified, with silent/throttled background execution (no notification spam).
- **WhatsApp calls & dynamic replies** - `WhatsAppCallAndMessageService.kt`
  auto-taps Answer on WhatsApp/Telegram audio+video calls and routes every
  incoming message to a dynamically generated Gemini reply (template only as
  offline fallback).
- **Protected system prompt** - `CorePromptBuilder.kt` keeps the core rules,
  JSON schema and tool definitions immutable in app code; the Prompt tab only
  exposes your personal context (name / preferences / style), appended at runtime.
- **Version-correct storage permissions** - `PermissionManager.kt` opens
  All Files Access with the package URI on Android 11+, requests legacy
  READ/WRITE on Android 10, and READ_MEDIA_* on Android 13+ (works up to Android 17).
- **Unkillable service** - restarts itself after task removal; START_STICKY
  foreground microphone service.
- **Modern UI** - Material 3 status badges, protected-prompt cards, voice
  engine picker (Gemini Live voices: Puck, Kore, Charon...).

## Features

- **Autonomous Agentic Loop (v1.1)** - `SQLAgentEngine` runs a
  **Think -> Act -> Verify -> Retry** ReAct loop. The agent re-reads the live
  screen after every action and keeps re-planning (up to 20 configurable steps)
  until the task is verified complete - e.g. *"open Instagram, like my latest reel"*.
- **Screen vision** - accessibility screenshots (API 30+, no MediaProjection
  permission needed) are sent to multimodal models (Gemini / vision models) so
  the AI can literally see the UI. Element dump includes bounds `[x,y WxH]`,
  text, content-description and clickable flags for precise coordinate taps.
- **Live call assistant (v1.1)** - InCallService auto-answers incoming calls,
  speaks through the call audio stream (male/female voice) and listens for the
  caller's reply (SpeechRecognizer). Also taps **Answer** on WhatsApp calls.
- **WhatsApp/SMS auto-reply (v1.1)** - NotificationListener triggers an AI
  generated reply (OTP-safe, rate-limited) that is typed and sent through
  accessibility.
- **Dynamic Gemini model selector** - the model dropdown is fetched live from
  `generativelanguage.googleapis.com/v1beta/models` for your key.
- **Default digital assistant** - VoiceInteractionService + VoiceInteractionSession,
  summonable with long-press HOME / power button.
- **24/7 wake-word listening** - foreground `microphone` service keeps a
  SpeechRecognizer loop alive for the wake word `SQL` (configurable).
- **Full phone control** - AccessibilityService that can:
  - read the live screen hierarchy (text + buttons + bounds),
  - tap any element by text or coordinates, swipe, scroll,
  - type into focused inputs (clipboard fallback),
  - open/close any installed app, press back/home/recents,
  - place calls (by number or contact name), answer/hang-up,
  - volume, brightness, flashlight, Wi-Fi/Bluetooth panels, system settings,
  - read your notifications (NotificationListenerService).
- **Voice customization (v1.1)** - Male/Female voice, pitch, speed, and
  Hindi / English / Hinglish for both speech output and wake-word recognition.
- **Free LLM providers** - Groq, Google Gemini, OpenRouter, Together AI,
  Hugging Face, DeepSeek and fully local Ollama (OpenAI-compatible).
- **Dashboard** - master toggle, live service health, voice-activity orb,
  typed-command tester and a real-time activity log.
- **System prompt editor** - full control over the AI's JSON action contract,
  personality and language.
- **Battery friendly** - foreground service, boot auto-restart, battery
  optimization bypass prompt, no cloud dependency except your LLM call.

---

## Repository structure

```
Jarvis-AI/
├── .github/workflows/android-ci.yml   # auto-build APK on every push
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml        # permissions + all 5 services
│       ├── java/com/sqlai/assistant/
│       │   ├── SqlAiApp.kt            # application + notification channels
│       │   ├── MainActivity.kt        # Compose shell + navigation
│       │   ├── core/
│       │   │   ├── SettingsRepository.kt   # DataStore settings + providers
│       │   │   ├── LogBus.kt               # live activity log
│       │   │   ├── StateBus.kt             # engine state machine
│       │   │   └── PermissionHelper.kt     # permission matrix + intents
│       │   ├── ai/
│       │   │   ├── AiClient.kt             # 7-provider chat handler
│       │   │   └── PlanParser.kt           # JSON action-plan parser
│       │   ├── device/
│       │   │   └── DeviceController.kt     # executes AI actions on phone
│       │   ├── engine/
│       │   │   ├── AssistantEngine.kt      # command -> AI -> actions
│       │   │   ├── Speaker.kt              # TTS replies
│       │   │   └── OverlayManager.kt       # floating status bubble
│       │   ├── service/
│       │   │   ├── ListeningService.kt         # 24/7 wake-word FGS
│       │   │   ├── SqlAccessibilityService.kt  # gestures + screen read
│       │   │   ├── SqlVoiceInteraction.kt      # default assistant
│       │   │   ├── SqlNotificationListener.kt  # notification access
│       │   │   └── BootReceiver.kt             # restart on reboot
│       │   └── ui/
│       │       ├── DashboardScreen.kt
│       │       ├── PermissionsScreen.kt
│       │       ├── ApiConfigScreen.kt
│       │       ├── PromptScreen.kt
│       │       ├── SettingsScreen.kt
│       │       ├── Components.kt
│       │       └── theme/Theme.kt
│       └── res/                     # vector icons, service configs, strings
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradlew / gradle/wrapper/
```

---

## Setup (5 minutes)

1. **Clone / fork** this repository.
2. Open it in Android Studio (Koala or newer) - Gradle sync downloads everything.
3. Run the `app` configuration on a device (API 26+).
4. In the app: **Access tab** → grant every permission (mic, accessibility,
   overlay, notification access, battery, default assistant).
5. **API tab** → pick a free provider → paste your key → *Test connection*.
6. Say **"SQL ... " followed by your command**, or type it on the dashboard.

### Free API keys

| Provider | Where to get a free key | Default model |
|---|---|---|
| Groq | https://console.groq.com/keys | `llama-3.1-8b-instant` |
| Gemini | https://aistudio.google.com/apikey | `gemini-1.5-flash` |
| OpenRouter | https://openrouter.ai/keys | `meta-llama/llama-3.1-8b-instruct:free` |
| Together | https://api.together.ai/settings/api-keys | `meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo` |
| Hugging Face | https://huggingface.co/settings/tokens | `meta-llama/Meta-Llama-3.1-8B-Instruct` |
| DeepSeek | https://platform.deepseek.com/api_keys | `deepseek-chat` |
| Ollama (local) | `ollama serve` + `ollama pull llama3.2` | `llama3.2` |

> Keys live only on your device (Jetpack DataStore). **Never commit keys to git.**

---

## Automatic APK builds (CI)

Every push to `main`/`master` runs `.github/workflows/android-ci.yml`:

1. JDK 17 + Gradle setup
2. `./gradlew assembleDebug`
3. APK uploaded as the **`SQL-AI-debug-apk` artifact** (Actions → run → Artifacts)

Tag a release to attach the APK automatically:

```bash
git tag v1.0.0
git push origin v1.0.0
```

→ a GitHub Release is created with `SQL-AI-latest.apk` attached.

---

## Permissions used (and why)

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | 24/7 wake-word listening |
| `FOREGROUND_SERVICE(_MICROPHONE)` | keep listening while screen is off |
| `BIND_ACCESSIBILITY_SERVICE` | read screen, tap, swipe, type |
| `SYSTEM_ALERT_WINDOW` | floating status bubble over other apps |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | read/reply to notifications |
| `WRITE_SETTINGS` | brightness control |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | survive aggressive OEM killers |
| `QUERY_ALL_PACKAGES` | resolve spoken app names to packages |
| `CAMERA` / `FLASHLIGHT` | torch control |
| Contacts / Phone / Media | call or message people, open media by name |
| `RECEIVE_BOOT_COMPLETED` | auto-restart after reboot |

---

## How a command flows

```
"SQL open WhatsApp"
   │
   ▼
ListeningService (wake word hit, mic loop)
   │
   ▼
AssistantEngine ── screen context (AccessibilityNodeInfo dump)
   │
   ▼
AiClient (Groq / Gemini / OpenRouter / ...)  →  JSON action plan
   │
   ├── reply text ──► Speaker (TTS)
   └── actions ─────► DeviceController
                         ├── open_app / tap_text / type_text
                         ├── swipe / scroll / press_key
                         └── volume / brightness / flashlight / settings
```

The model is instructed (via the system prompt) to **always** answer with:

```json
{"reply":"Opening WhatsApp", "actions":[{"type":"open_app","app":"whatsapp"}]}
```

---

## Security & privacy notes

- Screen text is only sent to the LLM when **Live screen context** is enabled
  (Settings tab). Disable it for fully offline/privacy-sensitive use with Ollama.
- All settings and keys stay in on-device DataStore.
- The listening loop runs **on-device** (Android SpeechRecognizer) - no server
  hears you except the LLM provider you configured.

## License

MIT - do whatever you want, just don't steal the credit.
