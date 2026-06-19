# Third-party notices — Kokoro TTS engine

This engine bundle redistributes the following third-party components. Each is licensed under the
Apache License, Version 2.0. A copy of that license is included as `LICENSE-Apache-2.0.txt` in this
directory.

## sherpa-onnx

- Project: sherpa-onnx (k2-fsa)
- Source: https://github.com/k2-fsa/sherpa-onnx
- License: Apache License 2.0
- Used as: the offline TTS runtime (Java API + JNI native libraries `libsherpa-onnx-jni` and the
  bundled ONNX Runtime), one native bundle per OS/arch target.

ONNX Runtime is distributed by sherpa-onnx within the native bundle and is itself licensed under
the MIT License (https://github.com/microsoft/onnxruntime).

## Kokoro model (kokoro-multi-lang-v1_0)

- Model: kokoro-multi-lang-v1_0
- Source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
- Upstream model: hexgrad/Kokoro-82M
- License: Apache License 2.0
- Used as: the neural TTS voice model (`model.onnx`, `voices.bin`, `tokens.txt`, espeak-ng data,
  lexicon) bundled with the engine so synthesis runs fully offline.

## espeak-ng data

- Project: espeak-ng
- License: GPL-3.0 for the espeak-ng program; the bundled `espeak-ng-data` phoneme/dictionary data
  is redistributed by the Kokoro model bundle as published in the sherpa-onnx `tts-models` release.

---

This bundle ships these components verbatim for end-user convenience. No modifications are made to
the upstream binaries or model files.
