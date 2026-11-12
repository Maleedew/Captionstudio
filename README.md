# Caption Studio — Sinhala + English auto captions (Android)

Native Android app (Kotlin + Jetpack Compose). Pick a video (up to 15 min), the app extracts the audio on the
phone, turns it into captions with a **free API** (Gemini or Groq), lets you edit text / timing / style, and
burns the captions into a new **MP4**.

Electric-blue / deep-black / smoke-white UI with rounded corners. Built to run on low-RAM, low-end-chip phones
(Android 8.0+): no FFmpeg, no on-device AI models, constant-memory audio pipeline, hardware video encoder.

---------------------------------------------------------------------------------------------------

## 1. Build the APK with GitHub Actions

1. Create a new **empty** GitHub repository and push this folder to it:
   ```bash
   cd CaptionStudio
   git init && git add . && git commit -m "Caption Studio"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
   (Make sure the hidden `.github` folder is included. If you upload through the website, drag the folder in.)
2. Open the **Actions** tab -> **Build APK**. It starts by itself on every push (or press *Run workflow*).
3. When it is green, open the run -> **Artifacts** -> `CaptionStudio-apk` -> unzip -> `CaptionStudio.apk`.
4. To give people a permanent download link: push a tag, e.g. `git tag v1.0.0 && git push --tags`.
   The APK is attached to the *Releases* page of the repo.

### Sign with your own key (do this once, before you share the APK)
Without it the APK is signed with a throw-away debug key. That installs fine, but later versions would not be able
to update over it. Create your key once:

```bash
keytool -genkeypair -v -keystore release.jks -alias captionstudio -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks        # macOS: base64 -i release.jks | tr -d '\n'
```
Repo -> Settings -> Secrets and variables -> Actions -> add:
`KEYSTORE_BASE64` (the base64 text), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (= captionstudio), `KEY_PASSWORD`.
Keep `release.jks` safe (it is git-ignored). Lose it and you can never update the app for existing users.

### Build locally (optional)
JDK 17 + Android SDK, then `./gradlew assembleRelease` -> `app/build/outputs/apk/release/`.

---------------------------------------------------------------------------------------------------

## 2. First run (each user does this once)

Settings -> pick a provider -> **Get free key** -> paste the key -> **Save** -> **Test**.

| Provider | Why | Free key |
|---|---|---|
| **Google Gemini** (default) | Best for Sinhala + English mixed speech | https://aistudio.google.com/app/apikey |
| **Groq Whisper** | Very fast, precise timing, weaker Sinhala | https://console.groq.com/keys |

Then: *Choose video* -> wait for captions -> fix words in **Captions** -> style them in **Style** -> **Export**.
The finished video is saved to `Movies/CaptionStudio` (Android 10+) and can be shared from the Export tab.

---------------------------------------------------------------------------------------------------

## 3. API-key security (important when you share the APK)

* **No key is inside the APK or the repo.** Anything inside an APK can be extracted in minutes, so a shared key
  would be stolen and used up. Every user brings their own free key instead.
* Keys are encrypted with **AES-256-GCM using a non-exportable Android Keystore key** (`SecureKeys.kt`); only
  ciphertext touches disk. Backups are disabled (`allowBackup=false` + data-extraction rules), traffic is
  HTTPS-only, keys are sent in headers (never in URLs) and never logged.
* If you really want viewers to use the app **without** their own key, you need a small server-side proxy that
  holds your key and rate-limits users (e.g. a free Cloudflare Worker). Ask for it as a next step; do not paste
  your key into the app code.
* Privacy note shown in the app: only the *audio* is uploaded, to the provider the user chose. Free tiers may
  use submitted content to improve the provider's products.

---------------------------------------------------------------------------------------------------

## 4. How it works

| Step | Implementation |
|---|---|
| Import | System file picker (`OpenDocument`) — no storage permission needed |
| Audio | `MediaExtractor` + `MediaCodec` -> 16 kHz mono, streamed to disk, cut at the quietest pause every ~4 min into WAV chunks (`AudioExtractor`, `ChunkPlanner`, `Resampler`) |
| Speech-to-text | Gemini `generateContent` (audio streamed as base64, JSON-schema output, automatic model discovery) or Groq `whisper-large-v3` (`Transcriber.kt`) |
| Captions | Clean-up, overlap removal, split to N words per caption, 100 % editable (`CaptionText.kt`) |
| Look | One renderer (`CaptionRenderer.kt`) draws fonts, outline, shadow, box, zoom/pop for **both** preview and export, so what you see is what you get. Sinhala shaping is done by Android's text engine. |
| Export | Media3 Transformer + `OverlayEffect` -> H.264/AAC MP4, 480p / 720p / original (`Exporter.kt`). The preview player is released during export so low-end phones never need two video decoders. |
| Safety net | The last project (captions + style) is autosaved, so a killed app (low RAM) loses nothing |

Bundled fonts (SIL OFL, see `app/src/main/assets/fonts/licenses`): Noto Sans Sinhala, Gemunu Libre, Yaldevi,
Abhaya Libre, Poppins, Anton. Latin-only fonts fall back to the system font for Sinhala letters automatically.

---------------------------------------------------------------------------------------------------

## 5. Limits & honest notes

* **Version 1 limit: 15 minutes** (`VideoUtil.MAX_VIDEO_MS`).
* Sinhala speech recognition is never perfect. That is why the editor is part of the app: fix words, split / merge,
  and nudge timing.
* Free tiers have quotas (requests per minute / day). The app waits and retries on per-minute limits, and for
  Gemini it switches to another free model if one is used up. Google renames models often: leave the model field
  empty in Settings (automatic), or type a model name there.
* Export speed depends on the phone's hardware encoder. Long videos on very weak phones can take several minutes:
  the screen is kept awake while it runs; use 480p if a long export fails.
* Video **import** and **export** need free storage roughly equal to the video size (cache + gallery copy).

## 6. Customise

* App id / name: `app/build.gradle.kts` (`applicationId`), `res/values/strings.xml`.
* Colours: `ui/Theme.kt` (`Pal`). Fonts: drop a `.ttf` in `assets/fonts` and add it to `Fonts` in `Style.kt`.
* Gradle wrapper 8.9, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.09.03, Media3 1.5.1 (the Android Studio "Ladybug" combo).
* `isMinifyEnabled` is off for the first build; turn it on in `app/build.gradle.kts` for a smaller APK once everything works.
