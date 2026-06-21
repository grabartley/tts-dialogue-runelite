# Third-party notices — Zonos GPU TTS engine

This engine bundle redistributes the following third-party components. A copy of the Apache
License, Version 2.0 is included as `LICENSE-Apache-2.0.txt` in this directory.

## Zonos-v0.1

- Project: Zonos (Zyphra)
- Source: https://github.com/Zyphra/Zonos
- Model: Zonos-v0.1 (transformer variant, `Zyphra/Zonos-v0.1-transformer`)
- License: Apache License 2.0
- Used as: the neural TTS model. It conditions delivery on a speaker embedding (from a short
  reference clip) and an 8-dimensional emotion vector
  `[happiness, sadness, anger, fear, surprise, disgust, neutral, other]`, which is how this plugin
  renders true offline emotion. Synthesis runs on the GPU (CUDA).

## PyTorch

- Project: PyTorch (`torch`, `torchaudio`)
- Source: https://github.com/pytorch/pytorch
- License: BSD-3-Clause (see https://github.com/pytorch/pytorch/blob/main/LICENSE)
- Used as: the deep-learning runtime. The bundle ships the **CUDA wheels** of PyTorch, which carry
  their own bundled CUDA runtime libraries, so the end user needs only a compatible NVIDIA driver,
  no separate CUDA toolkit install. NVIDIA's CUDA runtime/cuDNN libraries inside those wheels are
  redistributed under the NVIDIA Software License Agreement / cuDNN license bundled with the wheels.

## DAC (Descript Audio Codec)

- Project: descript-audio-codec
- Source: https://github.com/descriptinc/descript-audio-codec
- License: MIT
- Used as: the audio autoencoder Zonos decodes its tokens through, at 44.1 kHz. The engine reports
  this true sample rate to the plugin so audio is never pitch-shifted.

## eSpeak NG (phonemizer backend)

- Project: espeak-ng (via the `phonemizer` package Zonos uses for text normalization/phonemization)
- License: GPL-3.0 for the eSpeak NG program; its phoneme/dictionary data is redistributed as
  published with the engine's dependencies.

## Reference-voice bank

- The `voices/` directory holds short reference clips, one per voice id used by the plugin's
  `ZonosVoiceMap` (e.g. `human_male`, `elf_female`, `player_male`, `narrator_neutral`). Each clip is
  either an openly licensed or originally produced recording; provenance and license for each clip
  is tracked in `voices/ATTRIBUTION.md` inside the bundle. Clips are used only to derive a speaker
  embedding at runtime and are not redistributed as standalone audio products.

---

This bundle ships these components for end-user convenience so the plugin runs offline on the GPU
with no additional setup beyond the NVIDIA driver. No modifications are made to the upstream model
weights.
