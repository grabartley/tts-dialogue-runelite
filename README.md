# 🗣️ RuneLite TTS Dialogue Plugin

Bring your RuneScape adventures to **life** with full voice acting!  
This plugin reads **in-game dialogue out loud** using different AI voices for NPCs and the player character. Experience immersive conversations with unique voices for every race and gender! 🎧🧙‍♂️

> Powered by 🧠 [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + 🎮 RuneLite

---

## 🧩 TTS Engine

The plugin synthesizes dialogue **in-process** with the [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) model running on CPU through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). There is no Docker, no local HTTP server, and no network call at synthesis time. On first use the plugin downloads the Kokoro model bundle (~349 MB) once into `~/.runelite/tts-dialogue/` and caches it; every line after that is generated locally.

Model load and synthesis run on a dedicated background thread, so the game never stalls on the first line or on repeated dialogue. On Apple Silicon a typical line synthesizes in roughly 1.3–1.8 s of CPU time.

Every voice is a real, distinct Kokoro speaker. The audio you hear is the clean neural output as-is: no resampling pitch shift, no reverb, and no distortion. Character differences between races come from picking genuinely different speakers (accent, timbre, pitch), never from post-processing.

> The native sherpa-onnx library ships per-platform. `build.gradle` bundles the macOS Apple Silicon native jar by default; swap the `sherpa-onnx-native-lib-*` line for your platform when building elsewhere.

---

## ✨ Features

- 🧠 **In-process Kokoro TTS** - offline, on-device synthesis with no server or per-line network call
- 🔊 **Text-to-Speech for all dialogue** (NPC & Player)
- 🎭 **Race/Gender Voice Matrix** - 8 races × 2 genders plus player voices, each mapped to a distinct Kokoro speaker
- 🤖 **Static NPC Voice Table** - Race and gender resolve from a precomputed `npcId → {race, gender}` table baked into the plugin: one in-memory lookup, no network calls or downloads
- ⏩ **Smart Playback** - Cancels audio on skipped dialogue
- 🔄 **Sensible Fallbacks** - NPCs missing from the table fall back to a gender-appropriate human voice
- 🐛 **Debug Mode** - Detailed NPC voice resolution logging for troubleshooting

### 🎙️ Voice Matrix

Voices are drawn from the English speakers of the `kokoro-multi-lang-v1_0` bank (American `af_/am_`, British `bf_/bm_`). Each category maps to a unique speaker id, so no two categories sound alike.

| Category | Male | Female |
|----------|------|--------|
| 👤 **Player** | `am_michael` (16) | `af_heart` (3) |
| 👥 **Human** | `am_fenrir` (14) | `af_bella` (2) |
| 🧝 **Elf** | `bm_george` (26) | `bf_emma` (21) |
| ⛏️ **Dwarf** | `bm_lewis` (27) | `bf_isabella` (22) |
| 👺 **Goblin** | `am_puck` (18) | `af_sky` (10) |
| 🏔️ **Troll** | `am_onyx` (17) | `af_sarah` (9) |
| 💀 **Undead** | `am_echo` (12) | `af_nicole` (6) |
| 😈 **Demon** | `bm_daniel` (24) | `af_river` (8) |
| 🧙 **Wizard** | `bm_fable` (25) | `af_alloy` (0) |

The **Human** voices double as the fallback for any NPC missing from the table, and as the default for every NPC when **Automatic NPC Voices** is turned off.

### 🗂️ NPC Voice Table

Each NPC's race and gender come from a static, precomputed table bundled at `src/main/resources/npc-voices.json` (a flat `npcId → {race, gender}` map). At runtime, choosing a voice is a single in-memory lookup keyed by NPC id, so there are **no network requests and no large downloads** in the hot path. Ids not in the table fall back deterministically to Human/Male (or a gender-appropriate human voice when fallbacks are on).

The table is generated **offline** and can be regenerated and expanded over time:

```bash
# Regenerate src/main/resources/npc-voices.json from the OSRSBox monster dump
# plus the curated overrides in tools/overrides.json
python3 tools/generate_npc_voices.py
```

- `tools/generate_npc_voices.py` - the offline generator (not part of the plugin runtime). It classifies race/gender from a static OSRSBox monster dump with a deterministic, conservative keyword classifier, then merges authoritative overrides on top.
- `tools/overrides.json` - hand-curated, authoritative `npcId → {race, gender}` entries that always win. **Fix mistakes and add important peaceful NPCs here**, then regenerate. See `tools/README.md` for details.

---

## 🔧 Dev Setup

### Requirements

- ✅ Java 17
- 🛠️ Gradle (wrapper included)

### Clone the repo

```bash
git clone https://github.com/grabartley/tts-dialogue-runelite.git
cd tts-dialogue-runelite
```

There is nothing else to install: no Docker, no voice servers, no model files to fetch by hand. The Kokoro bundle downloads itself on first run.

### Build the plugin

```bash
./gradlew build
```

### Run in test client

To test the plugin in a standalone RuneLite client, run the `com.grahambartley.TTSDialoguePluginTest` class with the following VM options:

```text
-ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED
```

You can run it directly from your IDE (like IntelliJ) or configure it in `build.gradle` for CLI use.

Drop the built `.jar` into your RuneLite `plugins` folder or use RuneLite's External Plugin Manager if you know the vibes 🔌

---

## ⚙️ Configuration

- **Dialogue Volume** - Volume of the spoken dialogue (0–100)
- **Enable Automatic NPC Voices** - Pick a Kokoro voice per NPC from the static race/gender table. When off, every NPC uses the default Human voice.
- **Player Voice** - Which Kokoro voice the player character uses
- **Enable Voice Fallbacks** - When an NPC is missing from the table, fall back to a gender-appropriate human voice. When off, those NPCs use the single default voice.
- **Debug Mode** - Detailed NPC race/gender resolution logging

---

## 🚑 Troubleshooting

**🐢 First line is slow or silent:**
- The model downloads (~349 MB) and loads on first use. Give it a moment; later lines are fast.
- Check RuneLite logs for `Downloading Kokoro model bundle` and `Kokoro model loaded` messages.

**🔊 No audio output:**
- Check system audio is working and not muted.
- Confirm the model finished loading (look for `Kokoro model loaded in … ms` in the logs).

**🎭 Wrong or unexpected voice:**
- Enable **Debug Mode** to log the detected race/gender and the chosen Kokoro voice per NPC.
- Undetected NPCs intentionally fall back to the Human voice; toggle **Enable Voice Fallbacks** to change that behavior.

**💥 Native library errors on startup:**
- `build.gradle` bundles the macOS Apple Silicon sherpa-onnx native jar by default. On other platforms, swap the `sherpa-onnx-native-lib-*` dependency for your OS/arch.

---

## 🧠 Tech Stack

- Java 🥃
- Kokoro-82M (TTS) 🎙️
- sherpa-onnx (ONNX inference) 🧠
- RuneLite Plugin Framework 🧩

---

## 🎯 Future Ideas

- Custom voice overrides for specific NPCs 😈
- Optional per-category speed tuning via sherpa-onnx's native speed parameter

---

## 🙌 Shoutout

Big love to [hexgrad](https://huggingface.co/hexgrad/Kokoro-82M) for Kokoro, the [k2-fsa](https://github.com/k2-fsa/sherpa-onnx) team for sherpa-onnx, and the RuneLite devs for making plugin dev actually fun.

---

## 📬 Contribute

Got ideas? Found a bug? Shout in the issues 💥

---

**Made with love in Gielinor** 💖
