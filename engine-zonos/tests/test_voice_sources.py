"""Stdlib-only tests for the Zonos-id -> Kokoro-request mapping used to generate the reference bank.

These run on ANY machine: they import only ``scripts.voice_sources`` (which imports
``zonos_engine.voices``) and never the Kokoro engine, torch, or the Zonos package. Their job is to
prove every Zonos reference-voice id maps to a Kokoro request for the SAME race/gender/player, so the
generated reference clips keep voice identity consistent across the CPU and GPU backends.

Run from the ``engine-zonos`` dir with the stdlib only::

    python -m unittest discover -s tests
"""

import os
import sys
import unittest

# Make both ``scripts`` and ``zonos_engine`` importable when run from the engine-zonos dir.
_ENGINE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, _ENGINE_DIR)
sys.path.insert(0, os.path.join(_ENGINE_DIR, "scripts"))

import voice_sources  # noqa: E402
from zonos_engine import voices  # noqa: E402


class VoiceSourcesTest(unittest.TestCase):
    def test_mapping_covers_every_voice_id_exactly(self):
        # Every id build_bundle.py asserts on must have a Kokoro source, and there must be no extras.
        self.assertEqual(
            sorted(voice_sources.KOKORO_SOURCE_BY_VOICE_ID.keys()),
            voices.all_voice_ids(),
        )

    def test_each_id_resolves_to_same_race_gender_as_voices_table(self):
        # For every (player, race, gender) the engine's voices.voice_for can pick, the id we map back
        # to a Kokoro request must round-trip to that same id, proving identity lines up.
        for vid, req in voice_sources.KOKORO_SOURCE_BY_VOICE_ID.items():
            resolved = voices.voice_for(req.player, req.race, req.gender)
            self.assertEqual(
                vid,
                resolved,
                "voice id {} maps to a Kokoro request that resolves back to {}".format(
                    vid, resolved
                ),
            )

    def test_default_voice_uses_neutral_request(self):
        # narrator_neutral has no race/gender: it clones Kokoro's default speaker (empty voice block).
        req = voice_sources.kokoro_request_for(voices.DEFAULT_VOICE)
        self.assertFalse(req.player)
        self.assertIsNone(req.race)
        self.assertIsNone(req.gender)
        self.assertEqual(req.voice_block(), {"player": False})

    def test_player_voices_set_player_flag(self):
        male = voice_sources.kokoro_request_for("player_male")
        female = voice_sources.kokoro_request_for("player_female")
        self.assertTrue(male.player)
        self.assertTrue(female.player)
        self.assertEqual(male.gender, "MALE")
        self.assertEqual(female.gender, "FEMALE")

    def test_npc_voice_block_carries_race_and_gender(self):
        req = voice_sources.kokoro_request_for("dwarf_female")
        self.assertEqual(
            req.voice_block(), {"player": False, "race": "DWARF", "gender": "FEMALE"}
        )

    def test_unknown_id_raises(self):
        with self.assertRaises(KeyError):
            voice_sources.kokoro_request_for("not_a_real_voice")


if __name__ == "__main__":
    unittest.main()
