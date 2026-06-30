# Voiced Dialogue

<p align="center">
<a href="https://github.com/grabartley/tts-dialogue-runelite/stargazers"><img src="https://img.shields.io/github/stars/grabartley/tts-dialogue-runelite?logo=github&label=Stars&color=4078c0" alt="GitHub stars"></a>
<a href="https://github.com/grabartley/tts-dialogue-runelite/actions/workflows/cicd.yml"><img src="https://github.com/grabartley/tts-dialogue-runelite/actions/workflows/cicd.yml/badge.svg" alt="Build"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-yellow.svg" alt="License: MIT"></a>
<a href="https://ko-fi.com/grahambartley"><img src="https://img.shields.io/badge/Ko--fi-Support-009078?logo=ko-fi&logoColor=white" alt="Ko-fi"></a>
</p>

## Gielinor, out loud

Every quest, every shopkeeper, every back-alley stranger: **now they actually talk.** Voiced Dialogue gives NPCs and your own adventurer real AI voices in real time, turning silent text boxes into a living, breathing world you can hear.

Walk up, talk, and listen. That is the whole setup.

## What you get

- **A voice for everyone.** NPCs and the player each get a distinct voice, so a goblin never sounds like a king.
- **Thousands of NPCs, already voiced.** The plugin knows exactly who is speaking and picks the right voice from a bundled table of **over 13,700 NPCs**, matched by race and gender, with no lookups or lag mid-conversation. Bump into someone added in a future update? **Auto-learn** quietly looks them up on the wiki once and remembers them.
- **Accents and personalities with real craft.** **12 races and 13 regional origins** each map to their own accent: Scottish dwarves, South London trolls, Irish leprechauns, Dracula-esque vampyres, Norse Fremennik raiders, the gothic dread of Morytania, and more. On top of that, **over 6,400 NPCs** get a hand-written personality with its own style and speaking pace, so the icons of Gielinor sound like themselves.
- **Real emotion.** The plugin reads each speaker's chat-head expression and delivers the line happy, sad, angry, scared, or neutral, so a furious dwarf actually sounds furious.
- **You star in it too.** Set your own hero's accent, persona, and pace and play the dashing knight, the gruff mercenary, or the chaos goblin of your dreams.
- **Speak any language, any vibe.** Pipe dialogue through another language, or drop a delivery style over it: be a roadman in Gen Z slang among posh nobles, or run the whole realm as a pirate crew.
- **Atmosphere on tap.** Lines spoken underground pick up a cave echo, so dungeons and sewers feel enclosed.
- **Online or fully offline.** Choose a free cloud voice with full emotion and accents, or a free offline voice that never sends a thing off your machine. Your call.
- **Clean by default.** Always-on, offline profanity filtering keeps things friendly with no setup.
- **Fast and out of the way.** Synthesis and playback run off the game thread and replay from a cache, so the client stays snappy even when you mash through dialogue.

## Get started

Install from the **RuneLite Plugin Hub**: open RuneLite, click the wrench (Configuration) icon, open the **Plugin Hub**, search for **Voiced Dialogue**, and install.

Then pick how it sounds under the **Voice Backend** setting:

- **Cloud** (default): the full experience with emotion and accents. It needs a free [OpenRouter](https://openrouter.ai) API key. Create one, paste it into the config, and you are away. Until a key is set, cloud lines stay silent and a one-time notice points you to the key (or to Local).
- **Local**: a free, offline, no-key voice. Neutral and British, and nothing ever leaves your machine. On first use it quietly downloads a small engine for your OS into `~/.runelite/tts-dialogue/` and reuses it from then on.

The two backends are kept strictly separate: the one you choose is the only one that runs, and neither falls back to the other.

## Backends at a glance

| Backend | Where it runs | Emotion | Accents | Offline | Setup |
|---------|---------------|---------|---------|---------|-------|
| **Cloud (OpenRouter)** | Gemini 3.1 Flash TTS over HTTPS | Yes (5 moods) | Yes | No | a free OpenRouter API key |
| **Local (Kokoro)** | external CPU engine the plugin installs | Neutral only | British only | Yes | one-time engine download |

> **Privacy:** with Cloud active, the dialogue text being spoken is sent to OpenRouter over HTTPS using your key. Local stays fully offline and sends nothing off your machine. Either way, audio you have already heard replays from a local cache instead of being re-generated.

See [docs/backends.md](docs/backends.md) for the full architecture.

## The features, up close

<details>
<summary><b>Emotion from expressions</b></summary>

Each new line's emotion is read from the speaker's chat-head expression animation (the NPC head for NPC lines, the player head for yours) and mapped to one of five moods: Neutral, Happy, Sad, Angry, Scared. On Cloud, that mood rides along as an inline style tag so happy, sad, angry, and scared delivery is audibly distinct. The Local voice is neutral by design, so its lines stay neutral. Controlled by the **Emotional Delivery** toggle (on by default); turn it off to voice everything neutral.

The mapping is derived from the live OSRS cache and lives in [`src/main/resources/expression-emotions.json`](src/main/resources/expression-emotions.json). [docs/emotion-detection.md](docs/emotion-detection.md) covers exactly how it is harvested and regenerated.

</details>

<details>
<summary><b>Character profiles and accents</b> (Cloud)</summary>

On Cloud, every speaker gets a **character profile** that steers an accent, a persona, and a pace. This is a **British** medieval fantasy world: commoners speak plain common British, while royalty, knights, and high society get posh Received Pronunciation, with lore-driven exceptions (leprechauns Irish, vampyres Dracula-esque, trolls South London, dwarves Scottish, goblins mischievous and high).

Accents also follow where a character is **from**, taking the real-world cultures the lands are based on: the Kharidian Desert Middle Eastern, Karamja West African, the Fremennik lands Norse, Morytania gothic Eastern European, Varlamore Mediterranean, while the central kingdoms use distinct English regional accents. It is an origin signal, not where the NPC is standing, so a Misthalin guard visiting Karamja still sounds like home.

Profiles combine layers: a global British default, a per-race profile, an origin-based accent, every matching name-keyword category, and a per-NPC hand-written override. So an unknown NPC still gets a sensible British voice, while iconic characters get a bespoke one on top. Your own hero is fully editable (**Your Accent**, **Your Persona**, **Your Pace**), defaulting to a friendly, plucky adventurer with a Cambridge British accent. Gated by **Character Voices** (on by default, Cloud only).

NPCs added to the game since the last plugin update will not be in the bundled table. With **Auto-learn New NPCs** on (off by default), the plugin looks an unrecognised NPC's race, gender, and origin up on the wiki once in the background and caches it, so it voices correctly from the next line on.

</details>

<details>
<summary><b>Languages and speaking styles</b> (Cloud)</summary>

Set **Spoken Language** to anything other than English and each line is translated before it is voiced, with names and RuneScape terms preserved and translations cached per language. On top of that, a **Speaking Style** (Gen Z slang, pirate speak, Shakespearean, cyberpunk, and more) layers a delivery register, set independently for your own lines (**Player Speaking Style**) and NPC lines (**NPC Speaking Style**). So you can be a roadman among posh nobles, or turn the realm into a pirate crew.

</details>

<details>
<summary><b>Voices</b></summary>

Each speaker is resolved by race and gender from a static, precomputed lookup table bundled with the plugin, so picking a voice is a single in-memory lookup with no network calls. An NPC missing from the table uses the default Human voice.

**Local (Kokoro)** voices everyone with a British accent (Kokoro bakes accent into the speaker, so accent variety is a Cloud-only feature). Everyone gets a gender-correct British voice, spread across a small bank by a stable per-NPC hash so they still sound distinct.

| Gender | British voices |
|--------|----------------|
| **Male** | `bm_daniel`, `bm_fable`, `bm_george`, `bm_lewis` |
| **Female** | `bf_alice`, `bf_emma`, `bf_isabella` |

**Cloud (OpenRouter)** maps the same race and gender categories onto Gemini voices, giving each race its own character (gravelly dwarves and trolls, refined elves and wizards, bright goblins) with NPCs spread across a per-race sub-pool. Accent, persona, and pace are then steered per character.

The table is generated offline and can grow over time; see [docs/npc-voice-tooling.md](docs/npc-voice-tooling.md) for how it is built and extended.

</details>

<details>
<summary><b>Safety</b></summary>

Profanity filtering is always on, for everyone, with no toggle and no opt-out. Every spoken line (NPC dialogue, your own options, and other players' public chat) runs through a bundled, offline wordlist that bleeps profanity and slurs to asterisks before synthesis on both backends. Matching normalizes common evasions like leetspeak and inserted separators, while whole-word matching leaves lore words untouched. The three free-text profile fields you can type are additionally neutralized so they cannot inject a forged direction into the cloud prompt. All of it is local, single-pass, and adds no network call and no perceptible latency.

</details>

<details>
<summary><b>Performance</b></summary>

Synthesis and playback run off the game thread, so the client stays responsive and skipping a line cancels its audio instantly. Repeated lines are served from a cache keyed on backend, model, voice, emotion, and text: an in-memory layer for the session, plus a size-bounded on-disk cache under `~/.runelite/tts-dialogue/cache/` that survives restarts. The disk cache is capped by **Cache Size Limit**, evicts oldest-first, and keeps Cloud from being re-billed for audio you have already generated.

Cloud adds a few cost and latency guards: each line is capped at **Max Characters Per Line** and truncated at a sentence or word boundary, calls carry a 10-second timeout, a response that lands after you have skipped ahead is dropped, and two identical lines in flight share a single call. Requests reuse a keepalive connection pool and a byte-stable per-speaker profile block so Gemini's implicit prompt cache hits on repeats. Speculative prefetch (**Prefetch Dialogue**) warms the cache for the options you can see, so the line you pick next plays instantly.

</details>

## Configuration

Settings mirror the in-game panel: **General** (shared), **Cloud Voice (OpenRouter)** (Cloud only), and **Advanced** (niche tuning).

<details>
<summary><b>General</b></summary>

| Setting | Default | What it does |
|---------|---------|--------------|
| **Voice Backend** | `Cloud` | Selects the synthesis engine. `Cloud` (default) needs a free OpenRouter API key and, while active, sends your dialogue text to OpenRouter to be voiced. `Local` is a free, offline, no-key voice that is basic and neutral-only. The selected backend is the only one used; there is no fallback, so Cloud lines stay silent until a key is set. |
| **Player Voice** | `Type A` | The voice used for your own character's dialogue and public chat, on both backends. |
| **Dialogue Volume** | `20` | Loudness of the spoken dialogue, from `0` (muted) to `100`. |
| **Voice My Public Chat** | `Off` | Speaks your own public chat aloud in your player voice. Only your own messages are voiced, and chat is spoken exactly as typed (Spoken Language and Speaking Style are never applied to it). |
| **Prefetch Dialogue** | `On` | Warms the audio cache for the dialogue options you can see, so the line you pick next plays instantly. On Cloud it can raise spend on branches you never choose. |
| **Save Audio To Disk** | `On` | Saves synthesized dialogue to disk so repeated lines play instantly across sessions and Cloud is not re-billed. |
| **Speaking Pace** | `100` | How fast dialogue is spoken, as a percent of normal. Applies to both backends. |

</details>

<details>
<summary><b>Cloud Voice (OpenRouter)</b></summary>

| Setting | Default | What it does |
|---------|---------|--------------|
| **OpenRouter API Key** | empty | Your OpenRouter API key, required for the Cloud voice. Create a free key at openrouter.ai and paste it here; stored locally, never bundled with the plugin. |
| **Your Accent** | British (Cambridge) | Accent for your character's Cloud voice. Used with Character Voices on and Cloud active. |
| **Your Persona** | friendly and plucky | Persona and delivery style for your character's Cloud voice. |
| **Your Pace** | Normal | Speaking pace for your character's Cloud voice. |
| **Character Voices** | `On` | Gives each speaker a distinct voice (accent, style, pace) from the bundled table instead of one shared voice. Emotion still layers on top. Off gives the plainest, cheapest delivery. |
| **Emotional Delivery** | `On` | Carries the emotion detected from the speaker's chat-head animation through to the Cloud voice. Off voices every line as Neutral. |
| **Spoken Language** | `English` | Language dialogue is spoken in, from a dropdown of supported languages. `English` voices the original line; anything else translates each line first (preserving names, places, and item terms), then voices it. |
| **Player Speaking Style** | `None` | Optional delivery register layered onto your own lines (Gen Z slang, pirate speak, formal, and so on). Set independently of the NPC style. |
| **NPC Speaking Style** | `None` | Optional delivery register layered onto NPC lines, from the same set. Language-agnostic, so it composes with any Spoken Language. |
| **Auto-learn New NPCs** | `Off` | For an NPC not in the bundled table, looks its race, gender, and origin up on the OSRS Wiki once and caches it. Helps both backends. The first line still uses the default voice while the lookup runs. |
| **Cave Echo** | `Off` | Adds a decaying echo to dialogue spoken below the overworld (cave, dungeon, sewer, or basement). Cloud only. Applied at playback, so cached audio is unchanged. |

</details>

<details>
<summary><b>Advanced</b></summary>

| Setting | Default | What it does |
|---------|---------|--------------|
| **Cache Size Limit (MiB)** | `1024` | Maximum size of the on-disk audio cache. When a new clip would exceed it, the oldest are deleted first. Set to `0` for no limit. Only applies when Save Audio To Disk is on. |
| **Max Characters Per Line** | `0` | Caps how many characters of a line are sent to Cloud, truncating at a sentence or word boundary. `0` sends the whole line; set a positive value to bound worst-case per-line cost. |
| **Debug Logging** | `Off` | Logs detailed NPC race/gender resolution and the chosen voice per NPC. |

</details>

## For developers

**Requirements:** Java 17 and Gradle (wrapper included).

```bash
git clone https://github.com/grabartley/tts-dialogue-runelite.git
cd tts-dialogue-runelite
./gradlew build
```

Run the `com.grahambartley.TTSDialoguePluginRunner` class with VM options `-ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED`, either from your IDE or wired into `build.gradle`. The standalone TTS engine lives under `engine-kokoro/`, built and published to GitHub Releases by a manual workflow and resolved at runtime through the bundled `engine-manifest.json`. For how the jar and engine fit together and the release runbook, see [docs/engine-pipeline.md](docs/engine-pipeline.md).

**Tech stack:** Java, Kokoro-82M for the local voice, sherpa-onnx for ONNX inference, the OpenRouter speech API for the cloud voice, and the RuneLite plugin framework.

## Thanks

Voiced Dialogue stands on the shoulders of others: [hexgrad](https://huggingface.co/hexgrad/Kokoro-82M) for the Kokoro voice model, the [k2-fsa](https://github.com/k2-fsa/sherpa-onnx) team for sherpa-onnx, [OpenRouter](https://openrouter.ai) for routing the cloud voice, and the RuneLite devs for making plugin development genuinely fun.

## Contribute

Got ideas or found a bug? [Open an issue](https://github.com/grabartley/tts-dialogue-runelite/issues) and let's talk.

## License

Released under the [MIT License](LICENSE).
