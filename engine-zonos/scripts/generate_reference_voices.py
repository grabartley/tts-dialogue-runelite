#!/usr/bin/env python3
"""Generate the Zonos reference-voice bank by synthesizing each clip from the Kokoro engine.

Zonos-v0.1 is a zero-shot voice-cloning model: every voice it can speak is defined by a short
reference ``.wav``. This script populates ``engine-zonos/voices/`` with one ``<id>.wav`` for **every**
id the plugin's ``ZonosVoiceMap`` can emit, so ``packaging/build_bundle.py``'s completeness assertion
passes and a dispatched release yields a bundle that can voice every race/gender.

How each clip is produced (see ``voice_sources.py`` for the mapping rationale):

1. For each Zonos voice id, look up the Kokoro ``voice{race,gender,player}`` for the SAME
   race/gender/player (so "dwarf male" Zonos is cloned from Kokoro's "dwarf male", etc.).
2. Send one ``--stdio`` request to the Kokoro engine on a fixed neutral reference phrase, long enough
   to give Zonos a clean speaker embedding.
3. Read back the JSON header (``{"sampleRate":N,"samples":M,"format":"f32le"}``) and the M*4
   little-endian float32 PCM bytes, then write them as a mono 16-bit PCM WAV at the engine's native
   sample rate. Mono + a few seconds of clean speech is exactly what Zonos wants for conditioning.

The Kokoro engine runs on **CPU**, so this whole step is validatable on a dev machine with no GPU
(unlike the Zonos bundle build/synthesis, which needs Windows/NVIDIA).

The clips themselves are gitignored audio assets; this script (locally or in the release workflow)
produces them, they are never committed.

Usage (local dev)::

    # 1. Build the Kokoro engine image and stage its model (see docs/engine-pipeline.md):
    ./gradlew :engine:engineImage
    # ... download + extract the kokoro-multi-lang-v1_0 model into engine/build/engine-image/model
    # 2. Run the generator against the built launcher:
    python engine-zonos/scripts/generate_reference_voices.py \
        --engine-launcher engine/build/engine-image/kokoro-engine

The release workflow runs the same script before ``build_bundle.py``.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import subprocess
import sys
import wave

# Reuse the id -> Kokoro-request mapping (and, transitively, the authoritative id set).
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import voice_sources  # noqa: E402

# A neutral, content-light phrase a few seconds long. It carries no race/gender/emotion cues of its
# own (those come from the Kokoro speaker), giving Zonos a clean, representative embedding. The same
# phrase is used for every voice so clips differ only by speaker, not by content.
REFERENCE_PHRASE = (
    "Hello there, traveller. Welcome to the world. I have a few words to share with you, "
    "and I hope my voice serves you well on your journey ahead."
)


def _read_header_line(stream) -> str:
    """Read one header line byte-by-byte so the binary PCM frame that follows stays intact.

    A buffering reader would read past the newline and swallow the leading PCM bytes (the engine's
    own conformance test reads the header the same way for this reason).
    """
    chars = bytearray()
    while True:
        b = stream.read(1)
        if not b:
            break
        if b == b"\n":
            break
        if b != b"\r":
            chars += b
    return chars.decode("utf-8")


def _synthesize(proc, text: str, voice_block: dict):
    """Send one request to the engine and return ``(sample_rate, float_samples)``.

    ``proc`` is a running ``kokoro-engine --stdio`` process whose stdin/stdout we own.
    """
    request = {"text": text, "voice": voice_block, "speed": 1.0}
    line = json.dumps(request, separators=(",", ":")) + "\n"
    proc.stdin.write(line.encode("utf-8"))
    proc.stdin.flush()

    header_line = _read_header_line(proc.stdout)
    if not header_line:
        raise RuntimeError("engine closed the stream without a response header")
    header = json.loads(header_line)
    if "error" in header:
        raise RuntimeError("engine reported error: " + str(header["error"]))
    if header.get("format") != "f32le":
        raise RuntimeError("unexpected PCM format in header: " + repr(header.get("format")))

    sample_rate = int(header["sampleRate"])
    sample_count = int(header["samples"])
    raw = _read_exact(proc.stdout, sample_count * 4)
    samples = list(struct.unpack("<{}f".format(sample_count), raw)) if sample_count else []
    return sample_rate, samples


def _read_exact(stream, n: int) -> bytes:
    """Read exactly ``n`` bytes from ``stream`` or fail (the PCM frame is fixed-length)."""
    buf = bytearray()
    while len(buf) < n:
        chunk = stream.read(n - len(buf))
        if not chunk:
            raise RuntimeError(
                "engine produced a short PCM frame: expected {} bytes, got {}".format(n, len(buf))
            )
        buf += chunk
    return bytes(buf)


def _write_wav(path: str, sample_rate: int, samples) -> None:
    """Write mono 16-bit PCM WAV. Zonos resamples internally; mono clean speech is what it wants."""
    frames = bytearray()
    for s in samples:
        # Clamp to [-1, 1] before quantizing so a hot float sample cannot wrap the int16.
        clamped = -1.0 if s < -1.0 else (1.0 if s > 1.0 else s)
        frames += struct.pack("<h", int(round(clamped * 32767.0)))
    with wave.open(path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(bytes(frames))


def generate(launcher: str, out_dir: str, phrase: str) -> int:
    """Drive one persistent engine process to produce every clip. Returns the sample rate used."""
    os.makedirs(out_dir, exist_ok=True)
    voice_ids = voice_sources.all_voice_ids()

    proc = subprocess.Popen(
        [launcher, "--stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=None,  # let engine logs flow to this process's stderr
    )
    sample_rate = 0
    try:
        for vid in voice_ids:
            req = voice_sources.kokoro_request_for(vid)
            sr, samples = _synthesize(proc, phrase, req.voice_block())
            if not samples:
                raise RuntimeError("engine returned empty audio for voice id " + vid)
            sample_rate = sr
            out_path = os.path.join(out_dir, vid + ".wav")
            _write_wav(out_path, sr, samples)
            print(
                "wrote {} ({} samples @ {} Hz) from Kokoro {}".format(
                    out_path, len(samples), sr, req.voice_block()
                ),
                flush=True,
            )
    finally:
        try:
            if proc.stdin:
                proc.stdin.close()
        finally:
            proc.wait()

    print(
        "Generated {} reference clips into {} at {} Hz mono 16-bit PCM".format(
            len(voice_ids), out_dir, sample_rate
        ),
        flush=True,
    )
    return sample_rate


def main() -> int:
    engine_dir = os.path.dirname(HERE)  # engine-zonos/
    default_out = os.path.join(engine_dir, "voices")

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--engine-launcher",
        required=True,
        help="path to the built Kokoro engine launcher (e.g. engine/build/engine-image/kokoro-engine"
        " or kokoro-engine.bat on Windows)",
    )
    ap.add_argument(
        "--out-dir",
        default=default_out,
        help="directory to write <id>.wav clips into (default: engine-zonos/voices/)",
    )
    ap.add_argument(
        "--phrase",
        default=REFERENCE_PHRASE,
        help="reference phrase to synthesize for every clip (default: a fixed neutral phrase)",
    )
    args = ap.parse_args()

    launcher = os.path.abspath(args.engine_launcher)
    if not os.path.isfile(launcher):
        raise SystemExit("engine launcher not found: " + launcher)

    generate(launcher, args.out_dir, args.phrase)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
