# Synthesis backends

The plugin routes every dialogue line through one `SynthesisBackend`, chosen by the **Voice Backend**
config. `BackendProvider` resolves the active backend on every line, applies the emotion-downgrade
rule, and falls back to the local Kokoro voice (with a one-time notice) whenever the selected backend
is unavailable.

| Backend | Config value | Engine location | Emotion | Offline | Setup cost |
|---------|--------------|-----------------|---------|---------|------------|
| OpenRouter (Gemini) | `Cloud` (default) | OpenRouter speech API over HTTPS | Happy, Sad, Angry, Scared, Neutral | No | your own OpenRouter API key |
| Kokoro | `Local` | external CPU `--stdio` engine | Neutral only | Yes | one-time engine + model download |

Emotion is detected from each speaker's chat-head animation and rides in every request. The cloud
backend renders it as an inline Gemini style tag on the spoken text (`[happy]`, `[sad]`, `[angry]`,
`[fearful]`), so happy, sad, angry, and scared lines are audibly different; Neutral carries no tag. The
local Kokoro voice is neutral-only, so `BackendProvider` downgrades every line to Neutral for it.

## Cloud (OpenRouter) backend

The default (cloud-first). An OpenAI-compatible speech request over HTTPS to
`https://openrouter.ai/api/v1/audio/speech`, selected when **Voice Backend** is `Cloud`. It needs an
OpenRouter API key; until one is set it logs a one-time notice and falls back to the local voice. The
model is fixed to Google's **Gemini 3.1 Flash TTS**, the one OpenRouter speech model with both a voice
catalog rich enough to map every race and gender and full emotion support. Each NPC gets a
gender-correct Gemini voice by race, and two NPCs of the same race and gender are spread across a
sub-pool so they sound distinct but stable. The detected emotion is prepended to `input` as an inline
style tag (`GeminiEmotionStyle`); Neutral adds none. A per-speaker **character profile**
(`CharacterProfile`, resolved by `NpcProfileTable`) is also rendered as a leading `AUDIO PROFILE`
direction block setting accent/style/pace, so the profile sets the character and the emotion tag
colours the moment; the local backend ignores it. The body requests `response_format: "pcm"`, a
headerless 16-bit LE mono stream at 24 kHz decoded to the pipeline's native rate.

With this backend active, dialogue text leaves your machine and is sent to OpenRouter. A missing key,
an API error, or a network problem fails that line gracefully and falls back to the local voice with a
one-time notice.

### Cost and latency controls

Because the cloud backend is billed per character, several guards keep cost bounded and latency low:

- **Cache key.** `cacheVariant` folds in the model, the resolved Gemini voice, and (only when not at
  their defaults) the speaking pace, the character cap, the character profile, and a non-English
  spoken language, on top of the shared `(backendId, voiceKey, emotion, text)` identity. A model,
  voice, pace, profile, or language change therefore never replays the wrong audio, while a short
  English line stays on a stable key so changing a setting that cannot affect it does not force a
  needless re-bill.
- **Per-line character cap.** Each line is truncated to **Max Cloud Characters** (default 600) at a
  sentence boundary, or a word boundary if there is none, before sending. `0` disables it. OSRS lines
  are short, so this only bounds pathological cases.
- **In-flight de-duplication.** If two tasks reach the synth step for the same cache key at once, only
  the first issues a cloud call; the second waits on and reuses its result (`synthesizeDeduped`).
- **Timeout and stale-drop.** Cloud calls carry a 10-second `callTimeout` so a hung request cannot pin
  the single synthesis thread, and the pipeline's epoch check drops any response that arrives after the
  dialogue has advanced, so stale audio never plays late.
- **Speaking pace.** **Cloud Speaking Pace** is sent as the OpenRouter `speed` parameter only when it
  is not 100%, so the default request body is unchanged; the active model may ignore it.
- **Keepalive connection.** The backend reuses one long-lived client derived from the injected one
  (an 8-connection 5-minute keepalive pool, a 2s connect and 15s read budget), so back-to-back lines
  reuse a warm connection instead of re-handshaking. It is pinned to HTTP/1.1: the speech endpoint
  streams raw PCM, and HTTP/2 would multiplex the prefetch pool and the live line onto one
  connection where a concurrent streamed body can return truncated as an empty 200, so each
  concurrent call instead gets its own pooled connection. The same client backs the translation hop.
- **Empty-200 retry.** A 200 with a zero-byte body is a transient server glitch (the generation id
  is present but no audio came back), so the line is retried once before falling back; any other
  failure is not retried.
- **Fastest-provider routing.** Every request carries a `provider` block with `sort: "throughput"`
  (the `:nitro` equivalent), so OpenRouter routes to the lowest-latency provider for the model. An
  optional **Provider Region** adds a geographic bias to the same block when set.
- **Prompt-cache stabilisation.** The per-speaker character-profile block leads each request and is
  byte-stable (profile fields are trailing-trimmed at construction), so Gemini's implicit prompt
  cache hits on repeats for the same speaker, lowering input cost and time-to-first-byte.
- **Rate-limit back-off.** A `429` opens a geometric, capped back-off window; user lines still try,
  but speculative prefetch holds off (`isThrottled`) so the plugin never retry-storms a limit.

Beyond per-line guards, two larger levers cut perceived latency and broaden reach:

- **Speculative prefetch.** When dialogue options are visible, `DialoguePrefetcher` builds the exact
  request the player would speak for each option and warms it through `DialogueAudioService.prefetch`,
  which shares the in-flight dedup and both cache tiers but never plays audio or touches the playback
  epoch. A small fixed pool caps it at two requests in flight, a per-conversation cap bounds spend,
  already-cached lines are skipped, and leaving the node cancels still-queued prefetches. Gated by
  **Prefetch Dialogue Audio**.
- **Optional translation.** With **Spoken Language** set to anything but English, `OpenRouterTranslator`
  translates each line through `google/gemini-3.1-flash-lite-preview` (a fixed per-language system
  prompt for prompt-cache stability, preserving names and RuneScape terms) before the speech call,
  which then carries a BCP-47 `language_code`. The language is folded into the cache key, so a line is
  translated and billed at most once per language; a failed translation fails the line gracefully
  rather than voicing the wrong language.

Streaming the audio response to start playback sooner was evaluated and deferred: OSRS lines are short,
the full-buffer decode is already fast, and streaming would complicate the raw-PCM decode and the
epoch-based stale-drop for little perceived gain.

The primary cost lever remains the persistent disk cache, on by default, which keeps any already-heard
line from being billed again across sessions. Its footprint is bounded by the **Cache Size Limit**
(default 256 MiB) and evicted oldest-first (FIFO) so it never grows past the configured limit; a read
never rescues an old entry, and the just-written clip always survives. Setting the limit to `0` opts
out of eviction entirely, so the cache keeps every clip for users who would rather spend disk than
ever re-bill a line.

## Local (Kokoro) backend

An external CPU engine reached over a process transport (one JSON request line per synthesis, one
header line plus a raw float PCM frame back), spawned lazily off the game thread and kept alive across
lines. Neutral-only by deliberate design so the local voice stays clean neural output. It is the
universal fallback whenever the cloud backend is unavailable (no key, error, or offline).
