# RuneLite TTS Dialogue Plugin

<p align="center">
<a href="https://github.com/grabartley/tts-dialogue-runelite/stargazers"><img src="https://img.shields.io/github/stars/grabartley/tts-dialogue-runelite?logo=github&label=Stars&color=4078c0" alt="GitHub stars"></a>
<a href="https://github.com/grabartley/tts-dialogue-runelite/actions/workflows/cicd.yml"><img src="https://github.com/grabartley/tts-dialogue-runelite/actions/workflows/cicd.yml/badge.svg" alt="Build"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-yellow.svg" alt="License: MIT"></a>
<a href="https://ko-fi.com/grahambartley"><img src="https://img.shields.io/badge/Ko--fi-Support-009078?logo=ko-fi&logoColor=white" alt="Ko-fi"></a>
</p>

**Gielinor, out loud.** This plugin voices in-game dialogue in real time, giving NPCs and your own character distinct AI voices so every conversation actually speaks to you.

By default it runs entirely on your machine. No accounts, no cloud calls, no per-line API bills. The first time you talk to someone the plugin pulls down the voice model, and after that every line is synthesized locally, on-device. Two optional emotional backends are available for delivery that matches each line's detected mood: an offline **Local (GPU)** voice (Zonos) for machines with a supported GPU, and a **Cloud (Azure)** voice. Both are off by default and only used when you explicitly select them; the Cloud backend additionally needs your own Azure key. See [docs/backends.md](docs/backends.md).

> Powered by [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), running inside RuneLite.

## How The TTS Engine Works

The plugin synthesizes dialogue **in-process** with the [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) model running on CPU through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). On first use it downloads the Kokoro model bundle (~349 MB) once into `~/.runelite/tts-dialogue/` and caches it. Every line after that is generated locally on-device.

Model load, synthesis, and playback all run off the game thread on a single background pipeline fed by a small bounded queue, so the game stays responsive even when you mash through dialogue. Audio streams through a `SourceDataLine` straight from memory, and a two-tier cache keyed on the active backend, voice, emotion, and text replays repeated NPC lines instantly. A small in-memory LRU serves the current session; behind it a persistent on-disk cache under `~/.runelite/tts-dialogue/cache/` lets already-heard lines survive client restarts and, for cloud backends, avoids re-billing for audio you have already generated. The disk cache is size-bounded (256 MB by default) with least-recently-used eviction and is corruption-safe; it is on by default and can be turned off with the **Persistent Audio Cache** setting. On Apple Silicon a typical line synthesizes in roughly 1.3 to 1.8 seconds of CPU time; cached lines are immediate.

Every voice is a real, distinct Kokoro speaker, and what you hear is the clean neural output as-is. The differences between races come from picking genuinely different speakers: accent, timbre, and pitch.

> The native sherpa-onnx library ships per-platform. `build.gradle` bundles the macOS Apple Silicon native jar by default. Swap the `sherpa-onnx-native-lib-*` line for your platform when building elsewhere.

## Features

- **In-process Kokoro TTS** for offline, on-device synthesis with nothing leaving your machine.
- **Voice for all dialogue**, covering both NPCs and the player character.
- **Race and gender voice matrix** spanning 8 races times 2 genders plus player voices, each mapped to a distinct Kokoro speaker.
- **Static NPC voice table** where race and gender resolve from a precomputed `npcId -> {race, gender}` table baked into the plugin via a single in-memory lookup.
- **Smart playback** that streams off-thread, cancels instantly when you skip a line, and replays repeated lines from an in-memory LRU cache.
- **Emotional backends** for delivery that matches each line's detected mood: an offline **Local (GPU)** Zonos voice for supported GPUs and a **Cloud (Azure)** voice, both opt-in with graceful fallback to the local voice.
- **Persistent audio cache** on disk so repeated dialogue plays instantly across sessions and cloud backends are not re-billed; size-bounded with LRU eviction, corruption-safe, and opt-out-able.
- **Sensible fallbacks** so NPCs missing from the table still get a gender-appropriate human voice.
- **Debug mode** with detailed NPC voice resolution logging for troubleshooting.

### Voice Matrix

Voices are drawn from the English speakers of the `kokoro-multi-lang-v1_0` bank (American `af_/am_`, British `bf_/bm_`). Each category maps to a unique speaker id, so every category sounds distinct.

| Category | Male | Female |
|----------|------|--------|
| **Player** | `am_michael` (16) | `af_heart` (3) |
| **Human** | `am_fenrir` (14) | `af_bella` (2) |
| **Elf** | `bm_george` (26) | `bf_emma` (21) |
| **Dwarf** | `bm_lewis` (27) | `bf_isabella` (22) |
| **Goblin** | `am_puck` (18) | `af_sky` (10) |
| **Troll** | `am_onyx` (17) | `af_sarah` (9) |
| **Undead** | `am_echo` (12) | `af_nicole` (6) |
| **Demon** | `bm_daniel` (24) | `af_river` (8) |
| **Wizard** | `bm_fable` (25) | `af_alloy` (0) |

The **Human** voices double as the fallback for any NPC missing from the table, and as the default for every NPC when **Automatic NPC Voices** is turned off.

### NPC Voice Table

Each NPC's race and gender come from a static, precomputed table bundled at `src/main/resources/npc-voices.json` (a flat `npcId -> {race, gender}` map). At runtime, choosing a voice is a **single in-memory lookup keyed by NPC id**, kept entirely local to the hot path. Ids not in the table fall back deterministically to Human/Male, or to a gender-appropriate human voice when fallbacks are on.

The table is generated **offline** and can be regenerated and expanded over time:

```bash
# Regenerate src/main/resources/npc-voices.json from the OSRSBox monster dump
# plus the curated overrides in tools/overrides.json
python3 tools/generate_npc_voices.py
```

- `tools/generate_npc_voices.py` is the offline generator that builds the bundled table ahead of time. It classifies race and gender from a static OSRSBox monster dump with a deterministic, conservative keyword classifier, then merges authoritative overrides on top.
- `tools/overrides.json` holds hand-curated, authoritative `npcId -> {race, gender}` entries that always win. **Fix mistakes and add important peaceful NPCs here**, then regenerate. See `docs/npc-voice-tooling.md` for details.

## Dev Setup

### Requirements

- Java 17
- Gradle (wrapper included)

### Clone the repo

```bash
git clone https://github.com/grabartley/tts-dialogue-runelite.git
cd tts-dialogue-runelite
```

The Kokoro bundle downloads itself on first run, so cloning the repo is the only setup step.

### Build the plugin

```bash
./gradlew build
```

### Run in the test client

To test the plugin in a standalone RuneLite client, run the `com.grahambartley.TTSDialoguePluginTest` class with the following VM options:

```text
-ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED
```

You can run it directly from your IDE (such as IntelliJ) or configure it in `build.gradle` for CLI use.

Drop the built `.jar` into your RuneLite `plugins` folder, or load it through RuneLite's External Plugin Manager.

## External Engine Build & Release Pipeline

Alongside the in-process path, the repo ships a standalone, self-contained **Kokoro engine** under `engine/`. It is the same `kokoro-multi-lang-v1_0` model and the same sherpa-onnx configuration, repackaged as an external process that speaks a tiny line protocol so the plugin can run synthesis out-of-jar. The engine is built, signed, checksummed, and published to GitHub Releases by a manual pipeline, and the plugin reads a bundled `src/main/resources/engine-manifest.json` to find the right per-OS download.

### The `--stdio` protocol

The engine is launched as `kokoro-engine --stdio`. For each request the caller writes one JSON line to stdin and reads back one JSON header line immediately followed by the raw PCM frame:

```text
stdin  -> {"text":"hello","voice":{"race":"ELF","gender":"FEMALE","player":false},"emotion":"NEUTRAL","speed":1.0}
stdout <- {"sampleRate":24000,"samples":59242,"format":"f32le"}
stdout <- <samples * 4 little-endian float32 bytes>
```

`emotion` is accepted and ignored: Kokoro is neutral-only by design. `speed` defaults to `1.0`. The `voice` object maps to the same speaker matrix as the in-process path, so a given race/gender sounds identical either way. Run `kokoro-engine --selftest` to synthesize a fixed phrase and print the resulting sample rate and sample count, the local smoke test for a freshly built image.

### Target matrix

| Target | Runner | Archive | Native libs |
|--------|--------|---------|-------------|
| `linux-x64` | `ubuntu-latest` | `.tar.gz` | sherpa-onnx Linux x64 |
| `win-x64` | `windows-latest` | `.zip` | sherpa-onnx Windows x64 |
| `osx-aarch64` | `macos-14` | `.tar.gz` | sherpa-onnx macOS arm64 |
| `osx-x64` | `macos-13` | `.tar.gz` | sherpa-onnx macOS x64 |

Each bundle carries the application jar, the per-target sherpa-onnx native libraries, a self-contained `jlink` Java runtime (so end users need no JDK), the Kokoro model, and the Apache-2.0 attribution files under `licenses/`.

### Cutting a release (manual only)

Releases are **never automatic**. There is no tag-push or push-to-`main` release trigger. To cut one:

1. Open the **Actions** tab and select **Engine Release**.
2. Click **Run workflow**, enter the version tag (for example `v1.0.0`), and run it.
3. The pipeline builds all four targets on matching runners, runs the `--stdio` conformance test on the Linux bundle and a `--selftest` on every target, computes a sha256 per bundle, publishes them to a GitHub Release under that tag, regenerates `engine-manifest.json`, and opens a PR to update the bundled manifest so it matches the published artifacts.

The workflow is re-runnable: running it again for the same tag refreshes that release's assets.

### Signing secrets

Signing is **optional**. With no secrets configured the pipeline still completes and publishes **unsigned** bundles, and the macOS first-run fallback below covers Gatekeeper. Configure these repository secrets to enable signing/notarization:

| Secret | Enables |
|--------|---------|
| `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD`, `MACOS_CERT_P12`, `MACOS_CERT_PASSWORD` | macOS code-signing + notarization (all five required together) |
| `WINDOWS_CERT_PFX`, `WINDOWS_CERT_PASSWORD` | Windows Authenticode signing |

When a target is signed the manifest entry records `"signed": true`.

### macOS first-run fallback (unsigned bundles)

If a macOS bundle is unsigned and un-notarized, Gatekeeper blocks it on first launch. Two paths recover it:

- The plugin's installer clears the `com.apple.quarantine` extended attribute on the extracted engine after download.
- If a launch is still blocked, **right-click the `kokoro-engine` launcher in Finder and choose Open**, then confirm. macOS remembers the approval for subsequent runs.

Windows SmartScreen may warn on an unsigned bundle; choose **More info -> Run anyway**.

## Configuration

- **Dialogue Volume** sets the volume of the spoken dialogue (0 to 100).
- **Enable Automatic NPC Voices** picks a Kokoro voice per NPC from the static race/gender table. When off, every NPC uses the default Human voice.
- **Player Voice** chooses which Kokoro voice the player character uses.
- **Enable Voice Fallbacks** falls back to a gender-appropriate human voice when an NPC is missing from the table. When off, those NPCs use the single default voice.
- **Debug Mode** logs detailed NPC race/gender resolution and the chosen Kokoro voice per NPC for troubleshooting.
- **Voice Backend** selects the synthesis engine. `Local` is the offline, neutral-only Kokoro voice (default). `Local (GPU)` is the offline emotional Zonos voice and needs a supported GPU. `Cloud` routes synthesis through Microsoft Azure Neural TTS for emotional delivery. See [docs/backends.md](docs/backends.md) for the full comparison.
- **Enable Emotion** carries the emotion detected from each speaker's chat-head animation through to synthesis. When off, every line is voiced as Neutral. Because the default Local (Kokoro) backend is neutral-only by design, this only changes how a line *sounds* on an emotional backend (Local GPU Zonos or Cloud Azure); detection still runs either way.
- **Azure Subscription Key** / **Azure Region** configure the Cloud backend (for example `eastus`). These are required only when **Voice Backend** is `Cloud`; the key is stored locally and never bundled with the plugin.

### Emotion detection

Each new dialogue line's emotion is read from the speaker's chat-head expression animation: the NPC head for NPC lines, the player head for player lines. The animation id is mapped to an emotion via the bundled expression table; a missing head (sprite/objectbox dialogues), an idle head, or the one-tick race where the head animation lags the text all resolve to Neutral. The detection runs on the game thread as a cheap widget read and never blocks or throws. The resolved emotion rides in the synthesis request, but the active backend may downgrade it: the Local (Kokoro) backend is neutral-only, so emotion is only audible on an emotional backend, the Local (GPU) Zonos voice or the Cloud (Azure) voice.

### Local (GPU) backend

When **Voice Backend** is `Local (GPU)`, dialogue is synthesized fully offline by the Zonos voice (Apache-2.0), the only emotional path that keeps everything on your machine. It runs as a separate external engine reached through the same process transport as the local Kokoro engine, and renders detected emotion by conditioning Zonos on a per-emotion 8-dimensional emotion vector. Each race/gender resolves to a distinct Zonos reference voice, with a default for any unmapped speaker. This backend needs a supported GPU and a one-time engine download larger than the CPU engine; when no GPU is present or the engine is unavailable, dialogue falls back to the local voice with a one-time notice and never crashes or blocks the game thread. See [docs/backends.md](docs/backends.md) for the emotion-vector presets and GPU requirement.

### Cloud (Azure) backend

When **Voice Backend** is `Cloud` and a key and region are set, dialogue is synthesized by Azure Neural TTS over HTTPS using the injected RuneLite OkHttp client. This is the strongest-emotion path and renders detected emotion as Azure SSML styles. Each race/gender resolves to a distinct `en-US`/`en-GB` neural voice, mirroring the spirit of the local Kokoro matrix, with a default voice for any unmapped speaker. Emotion maps to an Azure SSML `mstts:express-as` style:

| Emotion | Azure style |
|---------|-------------|
| Neutral | plain (no style) |
| Happy   | `cheerful` |
| Sad     | `sad` |
| Angry   | `angry` |
| Scared  | `terrified` |

If the resolved voice does not support a requested style, delivery degrades to plain. A missing/invalid key, an API error, or a network problem fails that line gracefully and falls back to the local voice with a one-time notice; nothing crashes and the game thread is never blocked.

> With the Cloud backend active, dialogue text and your configured region leave your machine and are sent to Microsoft Azure. The local backend stays fully offline.

## Troubleshooting

**First line is slow or silent:**
- The model downloads (~349 MB) and loads on first use. Give it a moment; later lines are fast.
- Check RuneLite logs for `Downloading Kokoro model bundle` and `Kokoro model loaded` messages.

**No audio output:**
- Check that system audio is working and not muted.
- Confirm the model finished loading (look for `Kokoro model loaded in ... ms` in the logs).

**Wrong or unexpected voice:**
- Enable **Debug Mode** to log the detected race/gender and the chosen Kokoro voice per NPC.
- Undetected NPCs intentionally fall back to the Human voice; toggle **Enable Voice Fallbacks** to change that behavior.

**Native library errors on startup:**
- `build.gradle` bundles the macOS Apple Silicon sherpa-onnx native jar by default. On other platforms, swap the `sherpa-onnx-native-lib-*` dependency for your OS/arch.

## Tech Stack

- Java
- Kokoro-82M for text-to-speech
- sherpa-onnx for ONNX inference
- RuneLite plugin framework

## Future Ideas

- Custom voice overrides for specific NPCs.
- Optional per-category speed tuning via sherpa-onnx's native speed parameter.

## Shoutout

Big thanks to [hexgrad](https://huggingface.co/hexgrad/Kokoro-82M) for Kokoro, the [k2-fsa](https://github.com/k2-fsa/sherpa-onnx) team for sherpa-onnx, and the RuneLite devs for making plugin development genuinely fun.

## Contribute

Got ideas or found a bug? Open an issue and let's talk.

## License

Released under the [MIT License](LICENSE).
