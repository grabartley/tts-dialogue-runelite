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

The plugin routes every line through one synthesis backend, chosen by the **Voice Backend** config. The default is the cloud backend (cloud-first); it falls back to the offline local voice with a one-time notice until you add an OpenRouter API key, and whenever the cloud backend is otherwise unavailable, so dialogue keeps speaking.

| Backend | Config value | Where it runs | Emotion | Offline | Setup |
|---------|--------------|---------------|---------|---------|-------|
| **Cloud (OpenRouter)** | `Cloud` (default) | Gemini 3.1 Flash TTS via OpenRouter over HTTPS | Happy, Sad, Angry, Scared, Neutral | No | your own OpenRouter API key |
| **Local (Kokoro)** | `Local` | external CPU engine the plugin installs | Neutral | Yes | one-time engine + model download |

See [docs/backends.md](docs/backends.md) for the full comparison.

### Cloud (OpenRouter), the default

An opt-in cloud voice with near-zero setup beyond supplying a key. Create an OpenRouter API key, paste it into the config, and dialogue routes through Google's Gemini 3.1 Flash TTS over HTTPS, with a gender-correct voice picked per NPC by race and the line's detected emotion rendered as an inline style tag. On top of that, a **character profile** steers each speaker's accent, style, and pace (see [Character profiles](#character-profiles)). Until a key is set, the plugin logs a one-time notice and uses the free local voice instead.

> **Privacy:** with the Cloud backend active, the dialogue text being spoken is sent to OpenRouter over HTTPS using your API key. The local backend stays fully offline and sends nothing off your machine. The persistent cache means audio you have already heard is replayed from disk rather than re-billed.

### Local (Kokoro)

A real neural voice that runs on your CPU, fully offline. Nothing about a dialogue line leaves your machine. The plugin manages the engine for you: it downloads the right build for your OS on first use, runs it as a separate background process, and keeps it warm across lines. Delivery is neutral by design, so the local voice stays clean neural output, and it is the universal fallback whenever the cloud backend cannot run.

## Emotion

Each new dialogue line's emotion is read from the speaker's chat-head expression animation, the NPC head for NPC lines and the player head for player lines, and mapped to one of five emotions (Neutral, Happy, Sad, Angry, Scared). The resolved emotion rides in every synthesis request. The cloud backend renders it by prepending a Gemini inline style tag to the spoken text (`[happy]`, `[sad]`, `[angry]`, `[fearful]`; Neutral adds none), so happy, sad, angry, and scared delivery is audibly distinct. The local Kokoro voice is neutral-only by design, so its lines are downgraded to Neutral. Detection is controlled by the **Enable Emotion** toggle, which is on by default; when it is off, every line is voiced as Neutral.

## Character profiles

On the cloud backend, every speaker also gets a **character profile** that steers delivery: an accent, a style/persona, and a pace, rendered into a Gemini `AUDIO PROFILE` direction block in front of the line. Emotion still layers on top, so a profile sets the character and the emotion tag colours the moment. This is a **British** medieval fantasy world: commoners speak plain common British and only royalty, knights and high society use posh Received Pronunciation, and there are lore-driven exceptions (leprechauns Irish, vampyres Dracula-esque, trolls South London/Brixton, dwarves Scottish, goblins mischievous and high, wizards wise).

Accents also follow where a character is **from**: the far lands take the real-world cultures they are based on (the Kharidian Desert Middle Eastern, Sophanem and Menaphos Egyptian, Karamja West African, the Fremennik lands Norse, Morytania gothic Eastern European, Varlamore Mediterranean), while the central kingdoms use distinct English regional accents. This is an origin signal from the wiki, not where the NPC is standing, so a Misthalin guard exploring Karamja still sounds like home, and distinctive races keep their own accent everywhere.

Profiles are built by combining every matching layer: a global British default, a per-race profile, an ethnicity-based accent, every name-keyword category that matches (an NPC can be several things at once, like a Fremennik human or a ghost pirate), and a per-NPC bespoke override keyed by NPC id. The persona/style blends across all matches, while the accent, pace, and name take the most specific layer that sets them. So an unmatched NPC still gets a sensible British profile, while iconic NPCs get a hand-written one on top. Your own character's profile is editable in the config (**Player Accent**, **Player Style**, **Player Pace**), defaulting to a seasoned medieval adventurer. The whole feature is gated by the **Enable Character Profiles** toggle (on by default; cloud only, the local voice ignores it). The race, gender and ethnicity data is sourced from the Old School RuneScape Wiki; the bundled profile data and how to grow it live in [docs/npc-voice-tooling.md](docs/npc-voice-tooling.md#character-voice-profiles-cloud).

NPCs added to the game since the last plugin update won't be in the bundled table. With **Auto-learn New NPCs** enabled (off by default), the plugin looks an unrecognised NPC's race, gender and ethnicity up on the wiki once, in the background, and caches the result locally so it voices correctly from the next line on. The first line still uses the default voice while the lookup runs, and the local voice backend stays fully offline regardless.

The mapping is derived from the live OSRS cache: dialogue chat-head expressions are seq animations whose **name** encodes the mood. The standard dialogue system uses a generic block (`chathap`/`chatlaugh` -> Happy, `chatsad` -> Sad, `chatang` -> Angry, `chatscared`/`chatshock` -> Scared) for the player and most NPCs, and some NPCs have their own expression heads (`lore_lizard_chat_happy`, `kahlith_chat_disapproving`, ...). Only the non-neutral ids are tabled; any id not present, and `-1` (no animation or a stale head), resolves to Neutral, so a neutral expression, an unseen id, or a non-human head (trolls, ogres, monsters often emit unrelated ids) is a safe no-op.

The full list lives in [`src/main/resources/expression-emotions.json`](src/main/resources/expression-emotions.json), and [docs/emotion-detection.md](docs/emotion-detection.md) documents exactly how it is harvested from the cache and how to regenerate it when Jagex adds new expressions.

<details>
<summary>Core generic expression mapping</summary>

| Seq IDs | Cache name | Emotion |
|---|---|---|
| 567-570 | `chathap1-4` | HAPPY |
| 605-608 | `chatlaugh1-4` | HAPPY |
| 610-613 | `chatsad1-4` | SAD |
| 614-617 | `chatang1-4` | ANGRY |
| 571-574 | `chatshock1-4` | SCARED |
| 596-599 | `chatscared1-4` | SCARED |
| 588-591 | `chatneu1-4` (and other `chat*` poses) | NEUTRAL (default, not listed) |

Per-NPC expression heads (e.g. `lore_lizard_chat_happy` 4843, `peng_chat_sad` 5665,
`kahlith_chat_disapproving` 8215) are tabled individually in the resource file.

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

The table above is the local Kokoro voice bank. The Cloud backend maps the same race and gender categories onto Google's Gemini voices, keeping each category gender-correct and spreading NPCs of the same race and gender across a sub-pool so they still sound distinct.

## Performance

Synthesis and playback run off the game thread, so the client stays responsive even when you mash through dialogue, and skipping a line cancels its audio instantly. Repeated lines are served from a cache keyed on the active backend, model, voice, emotion, and text: an in-memory layer covers the current session, and a size-bounded persistent on-disk cache under `~/.runelite/tts-dialogue/cache/` replays already-heard lines across client restarts. The disk cache is capped by the **Cache Size Limit** setting and evicts oldest-first (FIFO) so it never grows past that limit, survives corruption safely, and keeps cloud backends from being re-billed for audio you have already generated. It is controlled by the **Persistent Audio Cache** setting.

The cloud backend adds a few cost and latency guards on top of the cache. Each line is capped at **Max Cloud Characters** and truncated at a sentence or word boundary before sending, so a pathological long line can never run up the per-character bill; OSRS lines are short, so this only bites edge cases. Cloud calls carry a 10-second timeout so a hung request cannot pin the pipeline, and a response that lands after you have already skipped ahead is dropped rather than played late. If two identical lines hit the synth step at once, only one cloud call is made and the second reuses its result.

Cloud requests are tuned for latency. They reuse one long-lived keepalive HTTP/2 connection so back-to-back lines skip the TCP/TLS handshake, and they ask OpenRouter to route to the fastest provider for the model. The per-speaker character-profile block leads each request and is byte-stable, so Gemini's implicit prompt cache hits on repeats for the same speaker (cheaper input, faster start). An optional **Provider Region** biases routing by geography. Speculative prefetch (**Prefetch Dialogue Audio**) warms the cache for the dialogue options you can see, so the line you pick next plays from cache; it runs off-thread behind the same dedup and cache tiers, no more than two requests in flight, capped per conversation, and holds off when the backend is rate-limited. With **Spoken Language** set to anything other than English, each line is translated by a lightweight model before it is voiced, with names and RuneScape terms preserved; translations are cached per language so the same line is never re-billed.

## Configuration

| Setting | Default | What it does |
|---------|---------|--------------|
| **Voice Backend** | `Cloud` | Selects the synthesis engine: `Cloud` (OpenRouter, falls back to local until a key is set) or `Local` (offline neutral Kokoro). |
| **Enable Emotion** | `On` | Carries the emotion detected from the speaker's chat-head animation through to synthesis. The Cloud voice renders happy, sad, angry, and scared delivery; the Local voice is neutral-only. Off voices every line as Neutral. |
| **Persistent Audio Cache** | `On` | Saves synthesized dialogue to disk so repeated lines play instantly across sessions and cloud backends are not re-billed. |
| **Cache Size Limit (MiB)** | `256` | Maximum size of the on-disk audio cache. When a new clip would exceed it, the oldest clips are deleted first (FIFO) so the cache never grows past this limit. |
| **Dialogue Volume** | `100` | Volume of the spoken dialogue (0 to 100). |
| **Enable Automatic NPC Voices** | `On` | Picks a voice per NPC from the race and gender table. When off, every NPC uses the default Human voice. |
| **Player Voice** | `Player Male` | The voice used for the player character. |
| **Enable Voice Fallbacks** | `On` | Falls back to a gender-appropriate human voice for NPCs missing from the table. When off, those NPCs use the single default voice. |
| **Debug Mode** | `Off` | Logs detailed NPC race/gender resolution and the chosen voice per NPC. |
| **OpenRouter API Key** | empty | Your OpenRouter API key. Required for the Cloud backend; stored locally and never bundled with the plugin. |
| **Max Cloud Characters** | `600` | Caps how many characters of a line are sent to the cloud backend, truncating at a sentence or word boundary. Bounds worst-case per-line cost. `0` disables the cap. |
| **Cloud Speaking Pace** | `100` | Speaking pace for the cloud backend as a percent of normal. Sent as the OpenRouter speed parameter only when not `100`; the active model may ignore it. No effect on the local backend. |
| **Provider Region** | empty | Optional geographic bias for OpenRouter provider routing, injected into the request when set. Blank lets OpenRouter pick the best provider automatically. Cloud only. |
| **Spoken Language** | `English` | Language dialogue is spoken in. `English` voices the original line directly; any other language translates each line first (preserving names, places, and item terms), then voices the translation. Adds a translation request per new line. Cloud only. |
| **Prefetch Dialogue Audio** | `On` | Warms the audio cache for the dialogue options you can see, so the line you pick next plays instantly. Raises Cloud API spend on branches you never choose. Cloud only. |

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
- OpenRouter speech API for the cloud voice
- RuneLite plugin framework

## Shoutout

Big thanks to [hexgrad](https://huggingface.co/hexgrad/Kokoro-82M) for Kokoro, the [k2-fsa](https://github.com/k2-fsa/sherpa-onnx) team for sherpa-onnx, and the RuneLite devs for making plugin development genuinely fun.

## Contribute

Got ideas or found a bug? Open an issue and let's talk.

## License

Released under the [MIT License](LICENSE).
</content>
</invoke>
