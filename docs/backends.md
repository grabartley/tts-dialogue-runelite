# Synthesis backends

The plugin routes every dialogue line through one `SynthesisBackend`, chosen by the **Voice Backend**
config. `BackendProvider` resolves the active backend on every line, applies the emotion-downgrade
rule, and falls back to the local Kokoro voice (with a one-time notice) whenever the selected backend
is unavailable.

| Backend | Config value | Engine location | Emotion | Offline | Setup cost |
|---------|--------------|-----------------|---------|---------|------------|
| Kokoro | `Local` (default) | external CPU `--stdio` engine | Neutral only | Yes | one-time engine + model download |
| Zonos | `Local (GPU)` | external GPU `--stdio` engine | Full set, 8-dim emotion vector | Yes | supported GPU + heavier one-time engine download |
| Azure | `Cloud` | Microsoft Azure Neural TTS over HTTPS | Full set, SSML styles | No | your own Azure key + region |

Emotion is detected from each speaker's chat-head animation and rides in every request. Because the
default Local (Kokoro) backend is neutral-only by design, emotion is only *audible* on an emotional
backend: the offline Local (GPU) Zonos voice or the Cloud (Azure) voice. Detection runs either way.

## Local (Kokoro) backend

The default. An external CPU engine reached over a process transport (one JSON request line per
synthesis, one header line plus a raw float PCM frame back), spawned lazily off the game thread and
kept alive across lines. Neutral-only by deliberate design so the local default stays clean neural
output. It is the universal fallback whenever another backend is unavailable.

## Local (GPU) backend (Zonos)

Zonos-v0.1 (Apache-2.0) is the only offline emotional path: true emotion that never leaves your
machine. It runs as a **separate** external engine from Kokoro, reached through the **same** process
transport, and is selected when **Voice Backend** is `Local (GPU)`.

### GPU requirement and availability

Zonos needs a CUDA GPU. There is no reliable JVM-side CUDA probe, so GPU detection is delegated to the
engine: on warm-up the plugin installs the per-OS Zonos engine bundle, spawns it, and runs a health
handshake. The backend reports itself available only when the engine installs, spawns, and its
handshake reports both ready and a usable GPU. When no GPU is present, the engine is not installed, or
no Zonos engine has been published for the platform, the backend is unavailable and dialogue falls
back to the local Kokoro voice with a one-time notice, never a crash and never a blocked game thread.

The Zonos engine plus GPU runtime is a heavier one-time download than the CPU engine. Nothing heavy
ships in the plugin jar; the engine bundle is fetched from a release and verified by sha256, the same
boundary the Kokoro engine uses.

### Emotion vectors

Zonos conditions delivery on a fixed-order 8-dimensional vector
`[happiness, sadness, anger, fear, surprise, disgust, neutral, other]`. The plugin owns one preset per
detected emotion. Zonos's authors note that emotion conditioning is somewhat entangled with pitch and
overall audio quality, so the presets are conservative: each expressive emotion is dominated by a
single primary dimension with a neutral floor blended in, which keeps them clearly separable while
avoiding the artifacting that fully-saturated vectors can cause. The `surprise`, `disgust`, and
`other` dimensions stay at zero because the detected emotion set does not include them.

| Emotion | Dominant dimension | Neutral floor |
|---------|--------------------|---------------|
| Neutral | `neutral` (1.0) | n/a |
| Happy   | `happiness` (0.8) | 0.2 |
| Sad     | `sadness` (0.8) | 0.2 |
| Angry   | `anger` (0.8) | 0.2 |
| Scared  | `fear` (0.8) | 0.2 |

### Voices

Each race/gender resolves to a distinct Zonos reference voice (the engine maps a reference id to a
speaker embedding), mirroring the spirit of the Kokoro and Azure voice maps, with a neutral default
voice for any unmapped speaker.

Because Zonos is a zero-shot cloning model, the player voice can additionally be cloned from a
user-supplied reference clip. The **Player Voice Clip** config item takes a path to a local `.wav`;
when it is set and valid, player-voice lines carry an optional `playerReferenceClip` field on the
`--stdio` request line and the engine clones from that clip instead of the bundled `player_*` voice.
This is Zonos-only and player-only: the field is absent for every NPC line and for the Kokoro/Azure
backends, so their request framing is unchanged. The plugin validates the file (exists, readable WAV,
sane length) and folds a custom-clip token into the cache key so a cloned player line never collides
with the default-player-voice cache entry; the engine performs its own decode-and-fallback to the
bundled player reference if the clip is unreadable. The clip never leaves the machine.

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
