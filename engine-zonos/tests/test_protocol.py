"""Framing tests for the Zonos engine's ``--stdio`` wire protocol.

These run on ANY machine: they import only ``zonos_engine.protocol``, ``zonos_engine.engine`` (with
the mock synthesizer), ``zonos_engine.voices`` and ``zonos_engine.emotion`` - never torch, CUDA or
the Zonos package. Their whole job is to prove the bytes the engine reads/writes match what the
plugin's ``ExternalEngineClient`` writes/reads, so the merged Java backend really can talk to this
engine.

Run from the ``engine-zonos`` dir with the stdlib only::

    python -m unittest discover -s tests

No pip install, no torch, no GPU.
"""

import io
import json
import os
import struct
import sys
import unittest

# Make the package importable when run from the engine-zonos dir.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from zonos_engine import emotion, protocol, voices  # noqa: E402
from zonos_engine import engine as engine_mod  # noqa: E402
from zonos_engine.synthesizer import MockSynthesizer  # noqa: E402


# The exact request line shape the plugin's ExternalEngineClient.encodeRequest writes, including the
# 8-dim emotionVector that LocalZonosBackend always sends.
def plugin_request_line(text="Hello there", player=False, race="ELF", gender="FEMALE",
                        emotion_name="ANGRY", vector=None):
    if vector is None:
        vector = [0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.2, 0.0]
    root = {
        "text": text,
        "voice": {"player": player, "race": race, "gender": gender},
        "emotion": emotion_name,
        "speed": 1.0,
        "emotionVector": vector,
    }
    # Plugin uses Gson; field order is text, voice, emotion, speed, emotionVector. Order is
    # irrelevant to the engine's JSON decode, so any compact JSON is a faithful stand-in.
    return json.dumps(root)


class DecodeRequestTest(unittest.TestCase):
    def test_decodes_full_plugin_request(self):
        req = protocol.decode_request(plugin_request_line())
        self.assertEqual(req.text, "Hello there")
        self.assertFalse(req.player)
        self.assertEqual(req.race, "ELF")
        self.assertEqual(req.gender, "FEMALE")
        self.assertEqual(req.emotion, "ANGRY")
        self.assertEqual(req.speed, 1.0)
        self.assertEqual(len(req.emotion_vector), 8)
        self.assertAlmostEqual(req.emotion_vector[0], 0.8)
        self.assertFalse(req.is_health)

    def test_decodes_player_request(self):
        line = plugin_request_line(player=True, race="HUMAN", gender="MALE")
        req = protocol.decode_request(line)
        self.assertTrue(req.player)

    def test_bare_request_without_vector(self):
        # A Kokoro-shaped request (no emotionVector) must still decode.
        line = json.dumps({"text": "hi", "voice": {"player": False, "race": "HUMAN",
                                                    "gender": "MALE"}, "emotion": "NEUTRAL",
                           "speed": 1.0})
        req = protocol.decode_request(line)
        self.assertIsNone(req.emotion_vector)
        self.assertEqual(req.text, "hi")

    def test_health_op_line(self):
        req = protocol.decode_request(json.dumps({"op": "health"}))
        self.assertTrue(req.is_health)


class HeaderFramingTest(unittest.TestCase):
    def test_header_is_compact_and_ordered(self):
        # Must match StdioProtocol.header: {"sampleRate":N,"samples":M,"format":"f32le"} exactly.
        self.assertEqual(
            protocol.header_line(44100, 3),
            '{"sampleRate":44100,"samples":3,"format":"f32le"}',
        )

    def test_error_line_shape(self):
        self.assertEqual(protocol.error_line("boom"), '{"error":"boom"}')

    def test_health_line_shape(self):
        self.assertEqual(
            protocol.health_line(True, True, "ok"),
            '{"ok":true,"gpu":true,"detail":"ok"}',
        )

    def test_samples_are_little_endian_float32(self):
        frame = protocol.encode_samples([0.0, 1.0, -1.0])
        self.assertEqual(len(frame), 12)
        decoded = struct.unpack("<3f", frame)
        self.assertEqual(decoded, (0.0, 1.0, -1.0))

    def test_write_response_round_trip(self):
        out = io.BytesIO()
        protocol.write_response(out, 44100, [0.25, -0.5])
        raw = out.getvalue()
        newline = raw.index(b"\n")
        header = json.loads(raw[:newline].decode("utf-8"))
        self.assertEqual(header["sampleRate"], 44100)
        self.assertEqual(header["samples"], 2)
        self.assertEqual(header["format"], "f32le")
        pcm = raw[newline + 1:]
        self.assertEqual(len(pcm), header["samples"] * 4)
        self.assertEqual(struct.unpack("<2f", pcm), (0.25, -0.5))


class VoiceMapParityTest(unittest.TestCase):
    """The engine voice map must mirror the plugin's ZonosVoiceMap exactly."""

    def test_npc_and_player_and_default(self):
        self.assertEqual(voices.voice_for(False, "ELF", "FEMALE"), "elf_female")
        self.assertEqual(voices.voice_for(False, "DWARF", "MALE"), "dwarf_male")
        self.assertEqual(voices.voice_for(True, None, "FEMALE"), "player_female")
        self.assertEqual(voices.voice_for(True, None, "MALE"), "player_male")
        # Unmapped race -> default.
        self.assertEqual(voices.voice_for(False, "PARROT", "MALE"), "narrator_neutral")
        # Unknown gender normalizes to male slot.
        self.assertEqual(voices.voice_for(False, "HUMAN", None), "human_male")

    def test_voice_bank_is_complete(self):
        ids = voices.all_voice_ids()
        self.assertIn("narrator_neutral", ids)
        self.assertIn("player_male", ids)
        self.assertIn("elf_female", ids)
        # 8 races * 2 genders + 2 player + 1 default = 19.
        self.assertEqual(len(ids), 19)


class EmotionVectorTest(unittest.TestCase):
    def test_uses_plugin_vector_when_present(self):
        vec = [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8]
        self.assertEqual(emotion.resolve_emotion_vector(vec, "NEUTRAL"), vec)

    def test_falls_back_to_named_preset(self):
        out = emotion.resolve_emotion_vector(None, "SAD")
        self.assertEqual(len(out), 8)
        self.assertAlmostEqual(out[emotion.SADNESS], 0.8)
        self.assertAlmostEqual(out[emotion.NEUTRAL], 0.2)

    def test_pads_short_vector(self):
        out = emotion.resolve_emotion_vector([0.5, 0.5], "NEUTRAL")
        self.assertEqual(len(out), 8)
        self.assertEqual(out[2], 0.0)

    def test_truncates_long_vector(self):
        out = emotion.resolve_emotion_vector([0.1] * 12, "NEUTRAL")
        self.assertEqual(len(out), 8)


class StdioLoopTest(unittest.TestCase):
    """Drive the real _run_stdio loop with the mock synthesizer to prove the end-to-end framing:
    plugin-shaped lines in, header+PCM / health / error lines out."""

    def _run(self, lines):
        synth = MockSynthesizer()
        stdin = io.StringIO("".join(line + "\n" for line in lines))
        out_buffer = io.BytesIO()

        class _Out:
            buffer = out_buffer

        real_stdin, real_stdout = sys.stdin, sys.stdout
        sys.stdin, sys.stdout = stdin, _Out()
        try:
            engine_mod._run_stdio(synth)
        finally:
            sys.stdin, sys.stdout = real_stdin, real_stdout
        return out_buffer.getvalue()

    def test_synthesis_line_produces_header_and_pcm(self):
        raw = self._run([plugin_request_line()])
        newline = raw.index(b"\n")
        header = json.loads(raw[:newline].decode("utf-8"))
        self.assertEqual(header["format"], "f32le")
        self.assertGreater(header["samples"], 0)
        self.assertEqual(header["sampleRate"], MockSynthesizer().sample_rate())
        pcm = raw[newline + 1:]
        self.assertEqual(len(pcm), header["samples"] * 4)

    def test_health_line_reports_not_gpu_for_mock(self):
        raw = self._run([json.dumps({"op": "health"})])
        reply = json.loads(raw.strip().decode("utf-8"))
        self.assertTrue(reply["ok"])
        self.assertFalse(reply["gpu"])  # mock never claims a GPU
        self.assertIn("detail", reply)

    def test_malformed_line_yields_error_then_keeps_going(self):
        raw = self._run(["{not json", plugin_request_line()])
        # First line: an error JSON line; then a valid header + PCM frame.
        first_nl = raw.index(b"\n")
        first = json.loads(raw[:first_nl].decode("utf-8"))
        self.assertIn("error", first)
        rest = raw[first_nl + 1:]
        second_nl = rest.index(b"\n")
        second = json.loads(rest[:second_nl].decode("utf-8"))
        self.assertEqual(second["format"], "f32le")


if __name__ == "__main__":
    unittest.main()
