# Synthesis backends

The plugin routes every dialogue line through one `SynthesisBackend`, chosen by the **Voice Backend**
config. `BackendProvider` resolves the active backend on every line, applies the emotion-downgrade
rule, and falls back to the local Kokoro voice (with a one-time notice) whenever the selected backend
is unavailable.

| Backend | Config value | Engine location | Emotion | Offline | Setup cost |
|---------|--------------|-----------------|---------|---------|------------|
| OpenRouter | `Cloud` (default) | OpenRouter speech API over HTTPS | Neutral (per-model emotion rolling out) | No | your own OpenRouter API key |
| Kokoro | `Local` | external CPU `--stdio` engine | Neutral only | Yes | one-time engine + model download |

Emotion is detected from each speaker's chat-head animation and rides in every request. Per-model
emotion rendering on the cloud backend is still being rolled out, so today every line is voiced as
Neutral on both backends. Detection runs either way, ready for emotional rendering once it lands.

## Cloud (OpenRouter) backend

The default (cloud-first). An OpenAI-compatible speech request over HTTPS to
`https://openrouter.ai/api/v1/audio/speech`, selected when **Voice Backend** is `Cloud`. It needs an
OpenRouter API key; until one is set it logs a one-time notice and falls back to the local voice. The
**Cloud Model** setting picks which OpenRouter speech model synthesizes each line (Gemini 3.1 Flash
TTS by default, the cheapest option). The body requests `response_format: "pcm"`, a headerless
16-bit LE mono stream at 24 kHz decoded to the pipeline's native rate.

With this backend active, dialogue text leaves your machine and is sent to OpenRouter. A missing key,
an API error, or a network problem fails that line gracefully and falls back to the local voice with a
one-time notice. The cache key folds in the active model so switching models never replays audio from
another model.

## Local (Kokoro) backend

An external CPU engine reached over a process transport (one JSON request line per synthesis, one
header line plus a raw float PCM frame back), spawned lazily off the game thread and kept alive across
lines. Neutral-only by deliberate design so the local voice stays clean neural output. It is the
universal fallback whenever the cloud backend is unavailable (no key, error, or offline).
