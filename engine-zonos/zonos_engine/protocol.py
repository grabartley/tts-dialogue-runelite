"""Pure encode/decode helpers for the ``--stdio`` wire protocol.

This module is the Python counterpart of the plugin-side
``com.grahambartley.synthesis.engine.ExternalEngineClient`` and the Kokoro engine's
``StdioProtocol`` (Java). It is intentionally free of any torch/CUDA/Zonos import so the wire
framing can be unit-tested on any machine (see ``tests/test_protocol.py``) without the heavy model
runtime.

Protocol (must match the plugin byte-for-byte):

* The plugin writes one JSON request line on stdin::

      {"text", "voice": {"player", "race", "gender"}, "emotion", "speed", "emotionVector": [...],
       "playerReferenceClip": "/abs/path.wav"}

  ``race`` and ``gender`` are the uppercase enum names the plugin sends (e.g. ``HUMAN``,
  ``MALE``). ``emotionVector`` is an 8-float array (Zonos emotion conditioning). Both
  ``emotionVector`` and ``playerReferenceClip`` are optional fields beyond the base Kokoro request,
  so a bare request still decodes. ``playerReferenceClip`` is a local file path the plugin sets only
  for player-voice lines on the Zonos backend (issue #50); the engine clones the player voice from
  it instead of the bundled ``player_*.wav``, falling back to the bundled default if the file is
  missing/unreadable/undecodable. It is absent for every NPC line and every other backend.

* For a synthesis request the engine writes one JSON header line::

      {"sampleRate": N, "samples": M, "format": "f32le"}

  immediately followed by exactly ``M * 4`` little-endian float32 bytes.

* A failed request yields a single parseable header line ``{"error": "..."}`` and no PCM frame, so
  the plugin recovers without a hung pipe.

* A health handshake request ``{"op": "health"}`` is answered with a single JSON line
  ``{"ok": bool, "gpu": bool, "detail": "..."}`` and no PCM frame. ``LocalZonosBackend.isAvailable``
  gates on ``gpu`` being true.

The header is emitted with ``separators=(",", ":")`` and the keys in the order
``sampleRate, samples, format`` so the bytes match the Java ``StdioProtocol.header`` output exactly.
"""

from __future__ import annotations

import json
import struct
from dataclasses import dataclass, field
from typing import List, Optional

FORMAT_F32LE = "f32le"

# Compact JSON, no spaces, matching Gson's compact serializer used on the plugin side.
_COMPACT = (",", ":")


@dataclass
class Request:
    """A decoded synthesis request line.

    Mirrors the fields the plugin's ``encodeRequest`` writes. ``emotion_vector`` is ``None`` when
    the request omitted it (a bare Kokoro-shaped request); the Zonos engine then falls back to its
    neutral preset.
    """

    text: str = ""
    player: bool = False
    race: Optional[str] = None
    gender: Optional[str] = None
    emotion: str = "NEUTRAL"
    speed: float = 1.0
    emotion_vector: Optional[List[float]] = None
    # Optional local path to a custom player reference clip (issue #50). ``None`` when the request
    # omits it (every NPC line and every non-Zonos request). Only consulted for player-voice lines.
    player_reference_clip: Optional[str] = None
    # The raw op, if any. A synthesis line has no "op"; a handshake line has {"op": "health"}.
    op: Optional[str] = None
    raw: dict = field(default_factory=dict)

    @property
    def is_health(self) -> bool:
        return self.op == "health"


def decode_request(line: str) -> Request:
    """Decode one stdin line into a :class:`Request`.

    Tolerant of missing fields exactly like the Java ``StdioProtocol.decodeRequest``: an absent
    ``voice``/``speed``/``emotionVector`` is fine. Raises ``ValueError`` only on malformed JSON,
    which the caller surfaces as an ``{"error": ...}`` line.
    """
    root = json.loads(line) if line else {}
    if not isinstance(root, dict):
        root = {}

    op = root.get("op")
    if op is not None and not isinstance(op, str):
        op = str(op)

    voice = root.get("voice") if isinstance(root.get("voice"), dict) else {}
    text = _as_str(root.get("text"), "")
    player = bool(voice.get("player", False))
    race = _as_str(voice.get("race"), None)
    gender = _as_str(voice.get("gender"), None)
    emotion = _as_str(root.get("emotion"), "NEUTRAL")

    speed_val = root.get("speed")
    try:
        speed = float(speed_val) if speed_val is not None else 1.0
    except (TypeError, ValueError):
        speed = 1.0

    emotion_vector = None
    vec = root.get("emotionVector")
    if isinstance(vec, list):
        try:
            emotion_vector = [float(v) for v in vec]
        except (TypeError, ValueError):
            emotion_vector = None

    # Optional custom player reference clip path. Only a non-empty string is meaningful; anything
    # else (absent, null, blank) means "use the bundled player reference".
    clip = root.get("playerReferenceClip")
    player_reference_clip = clip if isinstance(clip, str) and clip.strip() else None

    return Request(
        text=text,
        player=player,
        race=race,
        gender=gender,
        emotion=emotion,
        speed=speed,
        emotion_vector=emotion_vector,
        player_reference_clip=player_reference_clip,
        op=op,
        raw=root,
    )


def encode_samples(samples) -> bytes:
    """Encode an iterable of mono float samples to little-endian float32 bytes (the PCM frame)."""
    # struct with an explicit '<' is little-endian regardless of host byte order, matching the
    # ByteOrder.LITTLE_ENDIAN the plugin decodes with.
    seq = list(samples)
    return struct.pack("<%df" % len(seq), *seq)


def header_line(sample_rate: int, samples: int) -> str:
    """The JSON header line that precedes a PCM frame (no trailing newline)."""
    return json.dumps(
        {"sampleRate": int(sample_rate), "samples": int(samples), "format": FORMAT_F32LE},
        separators=_COMPACT,
    )


def error_line(message: Optional[str]) -> str:
    """A parseable error header line so a failed request never hangs the pipe."""
    return json.dumps({"error": "" if message is None else str(message)}, separators=_COMPACT)


def health_line(ok: bool, gpu: bool, detail: str = "") -> str:
    """The health handshake reply line consumed by ``ExternalEngineClient.handshake``."""
    return json.dumps(
        {"ok": bool(ok), "gpu": bool(gpu), "detail": detail or ""}, separators=_COMPACT
    )


def write_response(out_binary, sample_rate: int, samples) -> None:
    """Write a header line + PCM frame to a binary stdout stream, then flush.

    ``out_binary`` must be a binary stream (e.g. ``sys.stdout.buffer``); stdout is the binary PCM
    channel and must never be touched by text-mode writes, exactly like the Java side keeps stdout a
    clean binary channel.
    """
    pcm = encode_samples(samples)
    header = header_line(sample_rate, len(pcm) // 4) + "\n"
    out_binary.write(header.encode("utf-8"))
    out_binary.write(pcm)
    out_binary.flush()


def write_line(out_binary, line: str) -> None:
    """Write a single JSON line (health/error) to the binary stdout stream and flush."""
    out_binary.write((line + "\n").encode("utf-8"))
    out_binary.flush()


def _as_str(value, fallback):
    if value is None:
        return fallback
    return value if isinstance(value, str) else str(value)
