# Plugin Hub compliance checklist

Pre-submission audit of **Voiced Dialogue** (internal name `voiced-dialogue`) against the
[RuneLite Plugin Hub](https://github.com/runelite/plugin-hub) rules, focused on the
requirements the Hub review is strictest about: outbound network access, third-party API
usage, user-provided secrets, bundled binaries, and user consent. The cloud backend adds
outbound HTTPS to `openrouter.ai`, which is what this pass exists to cover.

This is the verification record. The step-by-step submission flow lives in
[`hub-submission.md`](hub-submission.md).

## Requirements

### Outbound HTTP uses the injected client only

**Verified.** No `new OkHttpClient()` / `new OkHttpClient.Builder()` anywhere in the cloud
path (`grep -rn "new OkHttpClient" src/` returns nothing). Every component receives
RuneLite's injected `OkHttpClient` and, where it needs different timeouts or a keepalive
pool, derives from it via `newBuilder()` (allowed):

- `TTSDialoguePlugin.java` — `@Inject private OkHttpClient okHttpClient;`, passed into every
  network component.
- `OpenRouterTtsBackend.java` — derives a keepalive client via
  `httpClient.newBuilder()...build()`.
- `OpenRouterTranslator.java` — derives a call-timeout client via `httpClient.newBuilder()`.
- `EngineInstaller.java` — downloads the Kokoro engine bundle through the injected client.
- `data/WikiNpcClient.java` — optional NPC auto-learn lookups, also through the injected
  client.

### All network and synthesis stays off the game thread

**Verified.** `DialogueAudioService` runs synthesis on dedicated daemon executors (a single
bounded synthesis thread, a warm-up thread for engine install/download, and a 2-thread
prefetch pool); the OpenRouter HTTP calls execute on those threads. NPC auto-learn lookups
run on their own `tts-wiki-learn` daemon thread. User-facing notices are hopped back to the
client thread via `clientThread.invokeLater(...)` in `ChatNoticeManager`. The game thread
never makes a network call or blocks on synthesis.

### API key is a secret, never logged, sent only to OpenRouter

**Verified.**

- Stored via the RuneLite config item `openRouterApiKey` declared `secret = true`
  (`TTSDialogueConfig.java`), so RuneLite masks it in the UI and config store.
- Read only to build the OpenRouter request `Authorization: Bearer <key>` header in
  `OpenRouterTtsBackend` and `OpenRouterTranslator`. Sent to no host other than
  `openrouter.ai`.
- Never logged: error logs record HTTP status, content-type, generation id, and a body
  snippet, never the key or the `Authorization` header. No `log.*` statement references the
  key.
- Never bundled: the key only ever exists in the user's local RuneLite config.

### In-plugin consent / privacy notice for cloud

**Verified.** Enabling Cloud is disclosed in multiple places, each stating plainly that
dialogue text leaves the machine:

- First-run onboarding chat notice (`ChatNoticeManager`): "...While Cloud is active your
  dialogue text is sent to OpenRouter to be voiced. Prefer to stay offline? Set Voice
  Backend to Local..."
- Voice Backend setting description (`TTSDialogueConfig`): "...while it is active your
  dialogue text is sent to OpenRouter to be voiced..."
- Cloud Voice section header (`TTSDialogueConfig`): "...While Cloud is active, your dialogue
  text is sent to OpenRouter to be voiced, so it leaves your machine."
- API Key field description: key is "Stored locally and never bundled with the plugin."
- The Hub listing itself carries the off-machine-data `warning=` in the descriptor (see
  [`hub-submission.md`](hub-submission.md) and
  [`plugin-hub-manifest/voiced-dialogue`](plugin-hub-manifest/voiced-dialogue)).

### Graceful behaviour when outbound network is blocked or the key is invalid

**Verified by code audit** (manual QA still required, see below). The OpenRouter backend
gates on `isAvailable()` (false when no key) and wraps every request in try/catch: non-2xx
responses, empty/undecodable/truncated audio, `IOException`, and unexpected
`RuntimeException` all return `null` after a one-time chat notice. A `null` synthesis result
leaves the single line unvoiced; nothing is thrown to the game thread. With no key set, the
line stays silent with a one-time "add an OpenRouter API key, or switch to Local" notice.
The Local Kokoro backend stays fully offline and is the user's no-key, no-network option.

### No secrets or large binaries in the built jar

**Verified by inspecting `build/libs` after `./gradlew jar`.** The jar is ~362 KiB
(well under the Hub's 10 MiB limit) and contains only compiled classes plus four data
resources, all loaded via `getResourceAsStream`:

| Resource | Size | What it is |
|----------|------|------------|
| `npc-voices.json` | ~2.0 MiB uncompressed | Precomputed NPC race/gender/ethnicity + voice profile table |
| `expression-emotions.json` | ~1.5 KiB | Chat-head animation → emotion map |
| `profanity.txt` | ~1.0 KiB | Offline profanity blocklist |
| `engine-manifest.json` | ~1.1 KiB | Per-OS engine download URLs + SHA-256 hashes |

No API keys, no model files, no native libraries, no `sherpa-onnx` or model-extraction
dependencies (`build.gradle` deliberately excludes them). The Kokoro engine (~380 MiB per
OS) is **not** bundled: `EngineInstaller` downloads it at runtime from GitHub Releases and
verifies it against the SHA-256 in `engine-manifest.json` before extracting.

## Hub listing text (for the descriptor / properties)

From `runelite-plugin.properties`:

- **displayName:** Voiced Dialogue
- **author:** Graham Bartley
- **description:** Voices NPC and player dialogue using local or cloud text-to-speech.
- **tags:** tts, voice, dialogue, audio, immersion, accessibility

Descriptor `warning=` (off-machine-data disclosure, from
[`plugin-hub-manifest/voiced-dialogue`](plugin-hub-manifest/voiced-dialogue)): "With the
Cloud (OpenRouter) voice backend selected, the dialogue text being spoken is sent to
OpenRouter over HTTPS using your API key. The local backend stays fully offline and sends
nothing off your machine."

## Manual QA still required before submission

Code review confirms the above, but the network-blocked path and jar contents should be
confirmed by hand on the target machine per `run-game-client`:

1. Run with the network disabled or an invalid key, Cloud selected, and confirm dialogue
   stays silent (or falls back to Local Kokoro) with a single chat notice, no crash, and no
   game-thread exception in the logs.
2. Inspect `build/libs/*.jar` contents and confirm no API key, no model binary, no
   disallowed dependency.
3. Confirm the logs never print the API key.
