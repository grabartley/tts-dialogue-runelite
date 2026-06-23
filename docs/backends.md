# Synthesis backends

The plugin routes every dialogue line through one `SynthesisBackend`, chosen by the **Voice Backend**
config. `BackendProvider` resolves the active backend on every line, applies the emotion-downgrade
rule, and falls back to the local Kokoro voice (with a one-time notice) whenever the selected backend
is unavailable.

| Backend | Config value | Engine location | Emotion | Offline | Setup cost |
|---------|--------------|-----------------|---------|---------|------------|
| Kokoro | `Local` (default) | external CPU `--stdio` engine | Neutral only | Yes | one-time engine + model download |
| Azure | `Cloud` | Microsoft Azure Neural TTS over HTTPS | Full set, SSML styles | No | your own Azure key + region |

Emotion is detected from each speaker's chat-head animation and rides in every request. Because the
default Local (Kokoro) backend is neutral-only by design, emotion is only *audible* on the Cloud
(Azure) backend. Detection runs either way.

## Local (Kokoro) backend

The default. An external CPU engine reached over a process transport (one JSON request line per
synthesis, one header line plus a raw float PCM frame back), spawned lazily off the game thread and
kept alive across lines. Neutral-only by deliberate design so the local default stays clean neural
output. It is the universal fallback whenever another backend is unavailable.

## Cloud (Azure) backend

Azure Neural TTS over HTTPS, selected when **Voice Backend** is `Cloud` and a key and region are set.
The strongest-emotion, lowest-setup path; it renders emotion as Azure SSML `mstts:express-as` styles.
With this backend active, dialogue text and your configured region leave your machine. A missing or
invalid key, an API error, or a network problem fails that line gracefully and falls back to the local
voice with a one-time notice.

| Emotion | Azure style |
|---------|-------------|
| Neutral | plain (no style) |
| Happy   | `cheerful` |
| Sad     | `sad` |
| Angry   | `angry` |
| Scared  | `terrified` |
