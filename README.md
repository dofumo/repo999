# Amadeus Companion

A small, personal fan project: a landscape voice-chat companion inspired by Kurisu Makise / Amadeus
from *Steins;Gate*. You hold a microphone button and talk; Gemini understands your **audio directly**,
answers in character, picks an expression, and the reply is spoken with Gemini TTS while the
sprite animates.

* All character art is **supplied by you** and is only loaded and displayed. No artwork is generated,
  downloaded, redrawn or replaced.
* Dialogue is original; no lines from the anime/game are reproduced.

## Target device

| | |
|---|---|
| OS | Android 9 (API 28), `minSdk 28` |
| CPU / ABI | ARMv7 `armeabi-v7a` (the app is pure Java: there are **no native libraries**, so it runs on any ABI) |
| RAM | 1.5 GB |
| Screen | 1280x800, landscape (`sensorLandscape`), 16:10 |
| Not required | Google Play Services, Android speech recognition |

Plain XML layouts, framework classes only. **No dependencies at all**: no Compose, Unity, Live2D, Vosk,
TensorFlow, Firebase, Play Services, `SpeechRecognizer`, `RecognitionService`, `RecognizerIntent` or
Google Speech Services.

## Building (no Android Studio needed)

### Option A: GitHub Actions (easiest)

1. Push this folder to a GitHub repository.
2. Open **Actions -> Build APK -> Run workflow** (it also runs on every push).
3. Download the artifact **AmadeusCompanion-debug-apk** and install `app-debug.apk`.

The workflow (`.github/workflows/build.yml`) installs JDK 17 and Gradle 8.7, creates the Gradle
wrapper if `gradle/wrapper/gradle-wrapper.jar` is not committed, runs `./gradlew assembleDebug` and
uploads the APK.

### Option B: command line

Requirements: JDK 17, Android command-line tools, internet access for the first build.

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=/path/to/android-sdk" > local.properties     # or export ANDROID_HOME

# The wrapper JAR is a binary and is not included. Create it once (needs a Gradle install):
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is about 39 MB because the sprites are bundled. The `- Copy` duplicates are kept in the
project to preserve your assets (about 6 MB); delete them if you want a smaller APK; the app never
references them.

## Gemini API key

1. Create a free key at <https://aistudio.google.com/apikey>.
2. Start the app, press **SETTINGS**, paste the key into **Gemini API key**, press **Save**.

The key lives only in the app's private SharedPreferences. Nothing is hardcoded.

## Models and API (verified against the Gemini docs, October 2026)

| Purpose | Default model |
|---|---|
| Conversation + audio understanding | `gemini-3.1-flash-lite` |
| Text to speech | `gemini-3.8-flash-lite-tts` |

Both names are editable in Settings (if Google renames them, change them there). Requests use the
Interactions API (`POST https://generativelanguage.googleapis.com/v1beta/interactions`, header
`x-goog-api-key`), which Google's current docs recommend for new projects. Every request uses
`store=false`, so no server-side interaction state is kept; the app sends its own short history.

## Direct audio pipeline (Android STT is NOT used)

```
mic button held
   -> AudioRecorder.start()          16 kHz mono PCM -> temporary WAV in the app cache
mic button released
   -> AudioRecorder.stop()
   -> SpriteManager.setThinking()    thinking sprite + hourglass
   -> GeminiClient.sendAudio()       the WAV goes to Gemini as inline audio; Gemini hears it itself
   -> Gemini returns ONE JSON object
   -> ConversationManager.update()
   -> MemoryManager.update()
   -> SpriteManager.setExpression(emotion)
   -> TtsManager.speak(reply)        only the reply text goes to Gemini TTS
   -> playback starts: SpriteManager.setTalking()
   -> playback ends:   SpriteManager.setIdle()
```

There is no `Microphone -> Android STT -> text -> Gemini` step anywhere. One normal voice turn is
exactly **one** conversation/audio request plus **one** TTS request. Transcription, emotion
classification, memory extraction and the reply all come back in the same JSON. The temporary WAV is
deleted right after it has been read. Nothing records in the background; maximum recording length is
15 s (configurable).

Response format requested from Gemini:

```json
{
  "heard": "short transcript of what you said",
  "reply": "what she says",
  "emotion": "neutral | joy | embarrassment | pride | serious | disgust",
  "speak": true,
  "memory_update": null
}
```

`memory_update` is `null`/empty, `{"add": "..."}` or `{"remove": "..."}`.
`heard` is one small addition to the format you specified: the raw audio is deleted after each
request, so the model also returns a one-line transcript of what it heard. That is how the recent
history can contain your side of the conversation without a second request and without Android STT.

## Sprites

The 862x1433 aspect ratio is always preserved: the character view has full screen height and its
width follows the bitmap's own proportions; sprites are scaled uniformly only. Sprites are decoded
lazily on a background thread (only the current state + expression), scaled to the on-screen size and
kept in a 14 MB byte-limited cache. Single-image animations never start a timer.

The three states are controlled by Android: `IDLE`, `THINKING`, `TALKING`. Gemini only chooses the
expression. The mapping is explicit in `SpriteManager.java` (no file-system ordering):

| Emotion | Idle | Talking |
|---|---|---|
| `neutral` | `40000a00 -> 40000b00` (a -> b -> a ...) | `40000b00 -> 40000b01 -> 40000b02` |
| `joy` | `40000600` | `40000600 -> 40000601 -> 40000602` |
| `embarrassment` | `40000800` | `40000800 -> 40000801 -> 40000802` |
| `pride` | `40000200` | `40000200 -> 40000201 -> 40000202` |
| `serious` | `40000300` | `40000300 -> 40000301 -> 40000302` |
| `disgust` | `40000c00` | `40000c00 -> 40000c01 -> 40000c02` |

* Files named `... - Copy.png` / `... - Copy (2).png` are byte-identical duplicates created by a file
  manager. They are not frames and are never decoded. For `idle_disgust` the canonical file is
  `CRS_JLD_40000c00 - Copy.png` (the directory only holds copies).
* Thinking: `sprites/thinking/CRS_JLE_40000700.png` (static, never sent to Gemini).
* Loading indicator: `sprites/loading_hour_glass/1..4.png`, shown while thinking.
* Background: `sprites/background/background.png`. Mic button: `sprites/buttons/mic_icon.png`.

Exact asset layout: `app/src/main/assets/sprites/{background,buttons,idle_all,talking_all,thinking,loading_hour_glass}`
as in the project tree, plus `fonts/console*.ttf` (the UI uses `console.ttf`; the fonts have no
Vietnamese glyphs, so Android substitutes a system font for those characters).

## Memory and conversation

* **Recent conversation**: last N turns (default 8, Settings), sent inside the system instruction.
  Older turns are dropped.
* **Long-term memory** (`MemoryManager`): a short list of facts (max 30, 200 characters each) in
  SharedPreferences. Gemini can add or remove entries through `memory_update`; the app never makes a
  separate request for memory, never saves every message, and never invents entries.
* Both can be cleared from Settings.
* Character definition: `assets/character_prompt.txt`. Voice and per-emotion delivery style:
  `assets/tts_prompt.txt` (Gemini 3.8 TTS reads the text verbatim, so style goes into
  `speech_metadata.style`, never into the spoken text).

## Settings

Gemini API key, conversation model, TTS model, TTS voice (default `Kore`), recent turn count,
maximum recording duration, maximum response length (characters), talking frame interval (ms),
clear recent conversation, clear long-term memory.

## Controls

* Hold the mic button to talk, release to send (touch, or hold Enter / OK / Space when the button is
  focused). Pressing it while she is speaking interrupts her.
* Optional text box + SEND as a fallback; it uses the same single-request path.

## Error handling

Missing microphone permission, unavailable microphone, empty recording, no network, invalid API key,
quota/rate limit, timeout, Gemini server error, malformed JSON, TTS failure and audio-playback failure
are all caught. A short message appears in the status line, the character returns to `IDLE`, and the
expression is left as it was. If only TTS fails, the text reply is still shown. Nothing retries in a
loop; the only retry is one request without the JSON-schema option if Gemini rejects that option.

## Project layout

```
AmadeusCompanion/
  .github/workflows/build.yml
  app/build.gradle
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/amadeus/companion/
      MainActivity  GeminiClient  AudioRecorder  TtsManager  SpriteManager
      ConversationManager  MemoryManager  AppSettings  GeminiResponse
  app/src/main/res/{drawable,layout,values,xml}
  app/src/main/assets/{character_prompt.txt,tts_prompt.txt,fonts,sprites}
  build.gradle  settings.gradle  gradle.properties  gradlew  gradlew.bat
```
