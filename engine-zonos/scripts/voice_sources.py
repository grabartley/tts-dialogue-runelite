"""Maps each Zonos reference-voice id to the Kokoro ``voice{race,gender,player}`` that should
synthesize its reference clip.

Zonos-v0.1 is a zero-shot voice-cloning model: every voice is defined by a short reference ``.wav``,
not a built-in speaker library. To keep voice identity consistent between the CPU (Kokoro) and GPU
(Zonos) backends, each Zonos voice id is cloned from the Kokoro speaker for the *same*
race/gender/player. That speaker is selected by the same request fields the plugin's
``ExternalEngineClient`` sends, which the engine's ``SpeakerMatrix`` resolves to a concrete Kokoro
speaker id.

This module is the single source of truth for that id -> Kokoro-request mapping. It is intentionally
**stdlib-only and import-light** (it imports ``zonos_engine.voices`` for the authoritative id set and
nothing heavy) so it can be unit-tested on any machine and reused by ``generate_reference_voices.py``.

The mapping is derived, not hand-listed: it inverts the same per-race (male, female) table the engine
uses (``zonos_engine.voices``), so it can never drift from the id set ``build_bundle.py`` asserts on.
``narrator_neutral`` (the default/fallback id) has no race/gender, so it is cloned from Kokoro's own
default speaker by sending an empty request, exactly as ``SpeakerMatrix`` resolves an unmapped race to
the human-male voice.
"""

from __future__ import annotations

import os
import sys
from typing import Dict, Optional

# Make ``zonos_engine`` importable when this module is run/imported from anywhere in the repo.
_ENGINE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ENGINE_DIR not in sys.path:
    sys.path.insert(0, _ENGINE_DIR)

from zonos_engine import voices  # noqa: E402


class KokoroRequest:
    """The ``voice`` block a Kokoro ``--stdio`` request carries for one reference clip.

    ``player`` mirrors the plugin's player flag; ``race`` / ``gender`` are the uppercase enum names
    the engine's ``SpeakerMatrix`` switches on. ``None`` for both (the neutral default) makes the
    engine fall back to its default speaker, matching ``ZonosVoiceMap``'s ``narrator_neutral``.
    """

    __slots__ = ("player", "race", "gender")

    def __init__(self, player: bool, race: Optional[str], gender: Optional[str]):
        self.player = player
        self.race = race
        self.gender = gender

    def voice_block(self) -> Dict[str, object]:
        """The ``voice`` object for a Kokoro request line (omitting ``None`` race/gender)."""
        block: Dict[str, object] = {"player": self.player}
        if self.race is not None:
            block["race"] = self.race
        if self.gender is not None:
            block["gender"] = self.gender
        return block

    def __eq__(self, other: object) -> bool:
        return (
            isinstance(other, KokoroRequest)
            and self.player == other.player
            and self.race == other.race
            and self.gender == other.gender
        )

    def __repr__(self) -> str:  # pragma: no cover - debug aid only
        return "KokoroRequest(player={!r}, race={!r}, gender={!r})".format(
            self.player, self.race, self.gender
        )


def _build_mapping() -> Dict[str, KokoroRequest]:
    """Invert ``zonos_engine.voices`` so each id resolves to the request that recreates its speaker.

    Built from the same tables ``voices.all_voice_ids`` enumerates, so the mapping is complete for
    every id ``build_bundle.py`` requires and cannot silently omit one.
    """
    mapping: Dict[str, KokoroRequest] = {}

    # Default / fallback voice: no race or gender, so Kokoro uses its default speaker. This matches
    # both ZonosVoiceMap.DEFAULT_VOICE and SpeakerMatrix's "unmapped race -> human male" fallback.
    mapping[voices.DEFAULT_VOICE] = KokoroRequest(player=False, race=None, gender=None)

    for gender_name, vid in voices._PLAYER_VOICES.items():
        mapping[vid] = KokoroRequest(player=True, race=None, gender=gender_name)

    for race_name, (male_id, female_id) in voices._NPC_VOICES.items():
        mapping[male_id] = KokoroRequest(player=False, race=race_name, gender="MALE")
        mapping[female_id] = KokoroRequest(player=False, race=race_name, gender="FEMALE")

    return mapping


# The authoritative id -> Kokoro-request mapping, built once at import.
KOKORO_SOURCE_BY_VOICE_ID: Dict[str, KokoroRequest] = _build_mapping()


def kokoro_request_for(voice_id: str) -> KokoroRequest:
    """Return the Kokoro request that synthesizes the reference clip for ``voice_id``.

    Raises ``KeyError`` if the id is not a known Zonos reference-voice id, so a typo fails loudly
    instead of producing a clip for the wrong speaker.
    """
    return KOKORO_SOURCE_BY_VOICE_ID[voice_id]


def all_voice_ids():
    """Every Zonos reference-voice id, mirroring ``zonos_engine.voices.all_voice_ids``."""
    return voices.all_voice_ids()
