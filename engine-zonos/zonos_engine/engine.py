"""Entry point for the standalone Zonos GPU TTS engine.

This is the Python counterpart of the Kokoro engine's ``KokoroEngineMain``. It speaks the exact same
``--stdio`` line protocol the plugin's ``ExternalEngineClient`` drives, plus the ``{ok, gpu}`` health
handshake that ``LocalZonosBackend.isAvailable()`` gates on.

Modes::

    zonos-engine --stdio      line protocol: JSON request on stdin -> JSON header + f32le PCM on
                              stdout; also answers {"op":"health"} handshake lines. Loops until
                              stdin closes.
    zonos-engine --selftest   load the model and synthesize a fixed phrase, printing sampleRate +
                              sampleCount; optionally write a wav (--wav PATH) to listen.
    zonos-engine --mock       force the mock tone synthesizer (framing validation only, no GPU);
                              combine with --stdio or --selftest. Never the shipped synthesis path.

stdout is a clean binary channel (header line + PCM, or a single JSON line for health/error).
Everything human-readable goes to stderr, matching the Java engine.
"""

from __future__ import annotations

import argparse
import os
import sys
from typing import Optional

from . import protocol
from .synthesizer import Synthesizer, build_synthesizer


def _bundle_root() -> str:
    """The directory the bundle is extracted into: the parent of this package at runtime.

    When frozen by PyInstaller, resources live next to the executable (``sys._MEIPASS`` or the exe
    dir). When run from source, the bundle root is the ``engine-zonos`` dir (two levels up from this
    file), so ``voices/`` resolves in both cases.
    """
    frozen = getattr(sys, "_MEIPASS", None)
    if frozen:
        return frozen
    env = os.environ.get("ZONOS_BUNDLE_ROOT")
    if env:
        return env
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main(argv: Optional[list] = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = argparse.ArgumentParser(prog="zonos-engine", add_help=True)
    parser.add_argument("--stdio", action="store_true", help="run the stdin/stdout line protocol")
    parser.add_argument(
        "--selftest", action="store_true", help="synthesize a fixed phrase and report rate/samples"
    )
    parser.add_argument(
        "--mock",
        action="store_true",
        help="use the mock tone synthesizer (framing only, no GPU, not real speech)",
    )
    parser.add_argument("--wav", default=None, help="(--selftest) write the audio to this wav path")
    args = parser.parse_args(argv)

    bundle_root = _bundle_root()
    synth = build_synthesizer(bundle_root, mock=args.mock)

    if args.stdio:
        return _run_stdio(synth)
    if args.selftest:
        return _run_selftest(synth, args.wav)

    parser.print_help(sys.stderr)
    return 2


def _run_stdio(synth: Synthesizer) -> int:
    """Read request lines from stdin until EOF, answering each on the binary stdout channel.

    A ``{"op":"health"}`` line gets a single ``{ok, gpu, detail}`` JSON line. A synthesis line gets
    a header line + PCM frame, or an ``{"error":...}`` line on failure so the plugin recovers
    without a hung pipe (mirrors KokoroEngineMain.runStdio)."""
    out = sys.stdout.buffer
    # Read stdin as text lines; decode is handled per line. Use the underlying buffer so we control
    # newline handling and never let text-mode buffering touch stdout.
    stdin = sys.stdin

    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = protocol.decode_request(line)
        except Exception as exc:  # noqa: BLE001 - malformed JSON: surface as an error line
            protocol.write_line(out, protocol.error_line("bad request: {}".format(exc)))
            print("Bad request line: {}".format(exc), file=sys.stderr)
            continue

        if req.is_health:
            _answer_health(out, synth)
            continue

        try:
            sample_rate, samples = synth.synthesize(
                req.text,
                req.player,
                req.race,
                req.gender,
                req.emotion,
                req.speed,
                req.emotion_vector,
            )
            protocol.write_response(out, sample_rate, samples)
        except Exception as exc:  # noqa: BLE001 - keep the process alive across one failure
            protocol.write_line(out, protocol.error_line(str(exc)))
            print("Synthesis failed: {}".format(exc), file=sys.stderr)

    synth.close()
    return 0


def _answer_health(out, synth: Synthesizer) -> None:
    """Answer a health handshake. ``ok`` is true once the engine is up and can probe the GPU; ``gpu``
    is true only when a usable CUDA device is present, which is exactly what the plugin gates on."""
    try:
        gpu = synth.cuda_available()
        detail = synth.gpu_detail()
        protocol.write_line(out, protocol.health_line(True, gpu, detail))
    except Exception as exc:  # noqa: BLE001 - never crash the handshake
        protocol.write_line(
            out, protocol.health_line(False, False, "health probe failed: {}".format(exc))
        )


def _run_selftest(synth: Synthesizer, wav_path: Optional[str]) -> int:
    """Synthesize a fixed phrase and report sampleRate + sampleCount on stdout (human-readable).

    Unlike ``--stdio`` this prints to normal stdout because no binary frame is involved; it is the
    standalone GPU smoke test a user runs after downloading the bundle, no game client needed."""
    text = "Zonos self test. The emotional voice engine is alive."
    sample_rate, samples = synth.synthesize(
        text,
        player=False,
        race="HUMAN",
        gender="MALE",
        emotion="HAPPY",
        speed=1.0,
        emotion_vector=None,
    )
    print("gpu={}".format(synth.cuda_available()))
    print("detail={}".format(synth.gpu_detail()))
    print("sampleRate={} samples={}".format(sample_rate, len(samples)))
    if not samples or sample_rate <= 0:
        print("Self-test produced empty audio", file=sys.stderr)
        return 1
    if wav_path:
        _write_wav(wav_path, sample_rate, samples)
        print("wrote {}".format(wav_path))
    synth.close()
    return 0


def _write_wav(path: str, sample_rate: int, samples) -> None:
    """Write mono float samples to a 16-bit PCM wav using only the stdlib, so the self-test can
    produce a listenable file without pulling in soundfile/torchaudio for the wav step."""
    import struct
    import wave

    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(sample_rate))
        frames = bytearray()
        for s in samples:
            clamped = max(-1.0, min(1.0, float(s)))
            frames += struct.pack("<h", int(clamped * 32767))
        w.writeframes(bytes(frames))


if __name__ == "__main__":
    raise SystemExit(main())
