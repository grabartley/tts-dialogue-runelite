# Voiced Dialogue

<p align="center">
<a href="https://github.com/grabartley/tts-dialogue-runelite/stargazers"><img src="https://img.shields.io/github/stars/grabartley/tts-dialogue-runelite?logo=github&label=Stars&color=4078c0" alt="GitHub stars"></a>
<a href="https://github.com/grabartley/tts-dialogue-runelite/actions/workflows/cicd.yml"><img src="https://github.com/grabartley/tts-dialogue-runelite/actions/workflows/cicd.yml/badge.svg" alt="Build"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-yellow.svg" alt="License: MIT"></a>
<a href="https://ko-fi.com/grahambartley"><img src="https://img.shields.io/badge/Ko--fi-Support-009078?logo=ko-fi&logoColor=white" alt="Ko-fi"></a>
</p>

**Gielinor, out loud.** This plugin voices in-game dialogue in real time, giving NPCs and your own character distinct AI voices so every conversation actually speaks to you.

Out of the box it runs entirely on your machine: no accounts, no cloud calls, no per-line API bills. NPCs and the player each get a voice picked by race and gender, the line's mood is read from the speaker's chat-head expression, and repeated dialogue replays instantly from a local cache. If you want spoken emotion, an opt-in cloud backend is available, and you choose it in the config.

> Powered by [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

## Install

Install from the **RuneLite Plugin Hub**: open RuneLite, click the wrench (Configuration) icon, open the **Plugin Hub**, search for **Voiced Dialogue**, and install it. The plugin jar itself is tiny and pure-JVM. The text-to-speech engine and its voice model live outside the jar and are obtained at runtime: the first time the local voice speaks, the plugin downloads and installs the matching engine for your operating system into `~/.runelite/tts-dialogue/`, verifies it, and reuses it from then on. Give that first line a moment; everything after is fast.

## Backends

The plugin routes every line through one synthesis backend, chosen by the **Voice Backend** config. The default is the offline local voice; the cloud emotional backend is opt-in. When a selected backend is unavailable, dialogue falls back to the local voice with a one-time notice and keeps speaking.

| Backend | Config value | Where it runs | Emotion | Offline | Setup |
|---------|--------------|---------------|---------|---------|-------|
| **Local (Kokoro)** | `Local` (default) | external CPU engine the plugin installs | Neutral | Yes | one-time engine + model download |
| **Cloud (Azure)** | `Cloud` | Microsoft Azure Neural TTS over HTTPS | Full set, SSML styles | No | your own Azure key + region |

See [docs/backends.md](docs/backends.md) for the full comparison and the Azure style map.

### Local (Kokoro), the default

A real neural voice that runs on your CPU, fully offline. Nothing about a dialogue line leaves your machine. The plugin manages the engine for you: it downloads the right build for your OS on first use, runs it as a separate background process, and keeps it warm across lines. Delivery is neutral by design, so the local default stays clean neural output, and it is the universal fallback whenever another backend cannot run.

### Cloud (Azure)

An opt-in cloud voice with the strongest emotion and near-zero setup beyond supplying a key. Create a Microsoft Azure Speech resource, then enter its subscription key and region (for example `eastus`) in the config. Azure renders each line's detected emotion as a neural SSML express-as style.

> **Privacy:** with the Cloud backend active, the dialogue text being spoken and your configured Azure region are sent to Microsoft Azure over HTTPS using your subscription key. The two local backends stay fully offline and send nothing off your machine. Azure offers a free tier with a monthly character allowance; the persistent cache means audio you have already heard is replayed from disk rather than re-billed.

## Emotion

Each new dialogue line's emotion is read from the speaker's chat-head expression animation, the NPC head for NPC lines and the player head for player lines, and mapped to one of five emotions (Neutral, Happy, Sad, Angry, Scared). The resolved emotion rides in every synthesis request and is rendered by the active backend (Azure renders it as an SSML style). The default local Kokoro voice speaks every line neutrally, so emotion becomes audible once you select the Cloud backend. Detection is controlled by the **Enable Emotion** toggle, which is on by default; when it is off, every line is voiced as Neutral.

The mapping is derived from the documented RuneScape chathead expression animation enum, a named set spanning ids **9760-9862**, with every documented expression mapped to the nearest of the five emotions. Any animation id not present in the table, and `-1` (no animation or a stale head), resolves to Neutral, so an unseen expression or a non-human head (trolls, ogres, children, monsters often emit ids outside the documented set) is a safe no-op. Five expressions do not map cleanly onto the five emotions and are mapped to the nearest one: `9800` MANIC_FACE -> Angry, `9812` LOOK_DOWN -> Sad, `9816` WHAT_THE -> Neutral, `9820` WHAT_THE_TWO -> Neutral, and `9824` EYES_WIDE -> Scared.

<details>
<summary>Full expression-to-emotion mapping</summary>

| Animation ID | Expression | Emotion |
|---|---|---|
| 9760 | NO_EXPRESSION | NEUTRAL |
| 9764 | SAD | SAD |
| 9768 | SAD_TWO | SAD |
| 9772 | NO_EXPRESSION_TWO | NEUTRAL |
| 9776 | WHY | NEUTRAL |
| 9780 | SCARED | SCARED |
| 9784 | MILDLY_ANGRY | ANGRY |
| 9788 | ANGRY | ANGRY |
| 9792 | VERY_ANGRY | ANGRY |
| 9796 | ANGRY_TWO | ANGRY |
| 9800 | MANIC_FACE | ANGRY * |
| 9804 | JUST_LISTEN | NEUTRAL |
| 9808 | PLAIN_TALKING | NEUTRAL |
| 9812 | LOOK_DOWN | SAD * |
| 9816 | WHAT_THE | NEUTRAL * |
| 9820 | WHAT_THE_TWO | NEUTRAL * |
| 9824 | EYES_WIDE | SCARED * |
| 9828 | CROOKED_HEAD | NEUTRAL |
| 9832 | GLANCE_DOWN | NEUTRAL |
| 9836 | UNSURE | NEUTRAL |
| 9840 | LISTEN_LAUGH | HAPPY |
| 9844 | TALK_SWING | NEUTRAL |
| 9847 | NORMAL | NEUTRAL |
| 9851 | GOOFY_LAUGH | HAPPY |
| 9855 | NORMAL_STILL | NEUTRAL |
| 9859 | THINKING_STILL | NEUTRAL |
| 9862 | LOOKING_UP | NEUTRAL |

`* mapped to the nearest of the five emotions (no surprise or confused category).`

</details>

## Voices

Each NPC's voice is chosen by race and gender, and the player has a dedicated voice. Race and gender come from a static, precomputed `npcId -> {race, gender}` lookup table bundled with the plugin, so choosing a voice at runtime is a single in-memory lookup with no network calls. The matrix spans eight races across two genders plus player voices, and each category maps to a distinct speaker so they sound genuinely different.

| Category | Male | Female |
|----------|------|--------|
| **Player** | `am_michael` | `af_heart` |
| **Human** | `am_fenrir` | `af_bella` |
| **Elf** | `bm_george` | `bf_emma` |
| **Dwarf** | `bm_lewis` | `bf_isabella` |
| **Goblin** | `am_puck` | `af_sky` |
| **Troll** | `am_onyx` | `af_sarah` |
| **Undead** | `am_echo` | `af_nicole` |
| **Demon** | `bm_daniel` | `af_river` |
| **Wizard** | `bm_fable` | `af_alloy` |

The Human voices double as the fallback for any NPC missing from the table, and as the default for every NPC when **Automatic NPC Voices** is off. The lookup table is generated offline and can be grown over time; see [docs/npc-voice-tooling.md](docs/npc-voice-tooling.md) for how it is built and how to add or correct entries.

## Performance

Synthesis and playback run off the game thread, so the client stays responsive even when you mash through dialogue, and skipping a line cancels its audio instantly. Repeated lines are served from a cache keyed on the active backend, voice, emotion, and text: an in-memory layer covers the current session, and a size-bounded persistent on-disk cache under `~/.runelite/tts-dialogue/cache/` replays already-heard lines across client restarts. The disk cache uses least-recently-used eviction, survives corruption safely, and keeps cloud backends from being re-billed for audio you have already generated. It is controlled by the **Persistent Audio Cache** setting.

## Configuration

| Setting | Default | What it does |
|---------|---------|--------------|
| **Voice Backend** | `Local` | Selects the synthesis engine: `Local` (offline neutral Kokoro) or `Cloud` (Azure). |
| **Enable Emotion** | `On` | Carries the emotion detected from the speaker's chat-head animation through to synthesis. Audible only on an emotional backend. |
| **Persistent Audio Cache** | `On` | Saves synthesized dialogue to disk so repeated lines play instantly across sessions and cloud backends are not re-billed. |
| **Dialogue Volume** | `100` | Volume of the spoken dialogue (0 to 100). |
| **Enable Automatic NPC Voices** | `On` | Picks a voice per NPC from the race and gender table. When off, every NPC uses the default Human voice. |
| **Player Voice** | `Player Male` | The voice used for the player character. |
| **Enable Voice Fallbacks** | `On` | Falls back to a gender-appropriate human voice for NPCs missing from the table. When off, those NPCs use the single default voice. |
| **Debug Mode** | `Off` | Logs detailed NPC race/gender resolution and the chosen voice per NPC. |
| **Azure Subscription Key** | empty | Your Azure Speech resource key. Required for the Cloud backend; stored locally and never bundled with the plugin. |
| **Azure Region** | empty | The region of your Azure Speech resource (for example `eastus`). Required for the Cloud backend. |

## Dev Setup

### Requirements

- Java 17
- Gradle (wrapper included)

### Clone and build

```bash
git clone https://github.com/grabartley/tts-dialogue-runelite.git
cd tts-dialogue-runelite
./gradlew build
```

### Run in the test client

Run the `com.grahambartley.TTSDialoguePluginTest` class with these VM options:

```text
-ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED
```

You can run it directly from your IDE (such as IntelliJ) or configure it in `build.gradle` for CLI use. The repo also ships the standalone TTS engine under `engine-kokoro/`, built and published to GitHub Releases by a manual workflow and resolved at runtime through the bundled `engine-manifest.json`. For how the jar and engine fit together and the engine release runbook (cutting a release, signing secrets, macOS Gatekeeper fallback), see [docs/engine-pipeline.md](docs/engine-pipeline.md).

## Tech Stack

- Java
- Kokoro-82M for the local voice
- sherpa-onnx for ONNX inference
- Microsoft Azure Neural TTS for the cloud voice
- RuneLite plugin framework

## Shoutout

Big thanks to [hexgrad](https://huggingface.co/hexgrad/Kokoro-82M) for Kokoro, the [k2-fsa](https://github.com/k2-fsa/sherpa-onnx) team for sherpa-onnx, and the RuneLite devs for making plugin development genuinely fun.

## Contribute

Got ideas or found a bug? Open an issue and let's talk.

## License

Released under the [MIT License](LICENSE).
</content>
</invoke>
