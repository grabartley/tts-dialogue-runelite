"""Emotion-vector handling for the Zonos engine.

The plugin owns the emotion -> 8-dim vector mapping in
``com.grahambartley.synthesis.ZonosEmotionVectors`` and sends the resolved ``emotionVector`` on the
request, so the engine's job is just to *consume* it. This module documents the fixed dimension
order Zonos expects and provides:

* :func:`resolve_emotion_vector` - take the vector the plugin sent (or, for a bare request / the
  ``--selftest`` path, fall back to a named preset) and return a clean 8-float list ready to hand to
  Zonos's conditioning.
* :data:`DIMENSIONS` and the dimension index constants, kept identical to the plugin so the two
  never drift.

Zonos-v0.1 conditions delivery on a fixed-order vector
``[happiness, sadness, anger, fear, surprise, disgust, neutral, other]``. The presets here are the
same conservative "primary axis + neutral floor" shape the plugin uses, so the engine can stand on
its own in ``--selftest`` without the plugin computing a vector.
"""

from __future__ import annotations

from typing import List, Optional

DIMENSIONS = 8

# Fixed dimension indices, in Zonos's documented order. Identical to ZonosEmotionVectors.java.
HAPPINESS = 0
SADNESS = 1
ANGER = 2
FEAR = 3
SURPRISE = 4
DISGUST = 5
NEUTRAL = 6
OTHER = 7

_PRIMARY = 0.80
_NEUTRAL_FLOOR = 0.20


def _neutral() -> List[float]:
    v = [0.0] * DIMENSIONS
    v[NEUTRAL] = 1.0
    return v


def _expressive(primary_dim: int) -> List[float]:
    v = [0.0] * DIMENSIONS
    v[primary_dim] = _PRIMARY
    v[NEUTRAL] = _NEUTRAL_FLOOR
    return v


# Named presets, mirroring the plugin so --selftest and bare requests still render emotion.
_PRESETS = {
    "NEUTRAL": _neutral(),
    "HAPPY": _expressive(HAPPINESS),
    "SAD": _expressive(SADNESS),
    "ANGRY": _expressive(ANGER),
    "SCARED": _expressive(FEAR),
}


def preset_for(emotion: Optional[str]) -> List[float]:
    """Return the named-preset vector for an emotion name, defaulting to neutral."""
    key = emotion.upper() if isinstance(emotion, str) else "NEUTRAL"
    return list(_PRESETS.get(key, _PRESETS["NEUTRAL"]))


def resolve_emotion_vector(
    emotion_vector: Optional[List[float]], emotion: Optional[str]
) -> List[float]:
    """Pick the emotion vector to condition on.

    Prefers the explicit ``emotionVector`` the plugin sent (the normal path). If it is missing
    (a bare request or the standalone ``--selftest`` path) it falls back to the named preset for
    ``emotion``. The result is always a clean list of exactly :data:`DIMENSIONS` floats: a
    too-short vector is zero-padded and a too-long one is truncated, so a malformed vector degrades
    rather than crashing synthesis.
    """
    if emotion_vector:
        vec = [float(v) for v in emotion_vector[:DIMENSIONS]]
        if len(vec) < DIMENSIONS:
            vec = vec + [0.0] * (DIMENSIONS - len(vec))
        return vec
    return preset_for(emotion)
