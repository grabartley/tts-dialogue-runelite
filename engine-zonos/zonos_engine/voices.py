"""Maps a plugin ``voice{player, race, gender}`` request onto a Zonos reference-voice id.

This mirrors the plugin-side ``com.grahambartley.synthesis.ZonosVoiceMap`` exactly: the same id
strings, the same per-race male/female slots, the same ``narrator_neutral`` default, and the same
"unknown gender is voiced with the male slot" normalization. Keeping the two tables identical means
either side can be reasoned about in isolation, and the engine never invents an id the plugin would
not expect.

The plugin only ever picks an id; the heavy work of resolving an id to a speaker embedding (from a
short reference clip bundled with the engine) lives here, in the engine. ``embedding_path_for``
resolves an id to the reference-audio file under ``voices/`` inside the bundle.
"""

from __future__ import annotations

import os
from typing import Optional

DEFAULT_VOICE = "narrator_neutral"

# Per-race (male, female) reference-voice ids. Keys are the uppercase NPCRace enum names the plugin
# sends. Mirrors ZonosVoiceMap.java.
_NPC_VOICES = {
    "HUMAN": ("human_male", "human_female"),
    "ELF": ("elf_male", "elf_female"),
    "DWARF": ("dwarf_male", "dwarf_female"),
    "GOBLIN": ("goblin_male", "goblin_female"),
    "TROLL": ("troll_male", "troll_female"),
    "UNDEAD": ("undead_male", "undead_female"),
    "DEMON": ("demon_male", "demon_female"),
    "WIZARD": ("wizard_male", "wizard_female"),
}

_PLAYER_VOICES = {"MALE": "player_male", "FEMALE": "player_female"}


def voice_for(player: bool, race: Optional[str], gender: Optional[str]) -> str:
    """Resolve the Zonos reference-voice id for a request, falling back to :data:`DEFAULT_VOICE`.

    Matches ``ZonosVoiceMap.voiceFor``: unknown gender normalizes to the male slot, an unmapped race
    falls back to the default, and a player request uses the player slots.
    """
    g = _normalize_gender(gender)
    if player:
        return _PLAYER_VOICES.get(g, DEFAULT_VOICE)
    slots = _NPC_VOICES.get(race.upper() if isinstance(race, str) else "")
    if slots is None:
        return DEFAULT_VOICE
    male, female = slots
    return female if g == "FEMALE" else male


def _normalize_gender(gender: Optional[str]) -> str:
    return "FEMALE" if isinstance(gender, str) and gender.upper() == "FEMALE" else "MALE"


def all_voice_ids():
    """Every reference-voice id this map can resolve, including the default. Used by packaging to
    assert the reference-audio bank is complete before a bundle is built."""
    ids = {DEFAULT_VOICE}
    ids.update(_PLAYER_VOICES.values())
    for male, female in _NPC_VOICES.values():
        ids.add(male)
        ids.add(female)
    return sorted(ids)


def voices_dir(bundle_root: str) -> str:
    """Directory holding the reference-audio bank inside an extracted bundle."""
    return os.path.join(bundle_root, "voices")


def embedding_path_for(bundle_root: str, voice_id: str) -> str:
    """Resolve a reference-voice id to its reference-audio wav inside the bundle."""
    return os.path.join(voices_dir(bundle_root), voice_id + ".wav")
