"""Stdlib-only tests for the launcher-command construction in ``generate_reference_voices``.

These guard the cross-platform launcher spawning without needing a real Windows runner: a ``.bat``
/``.cmd`` launcher must be wrapped in ``cmd /c`` (Python's ``subprocess``/``CreateProcess`` cannot
execute a batch file directly), while a unix launcher must be spawned directly exactly as before.

Run from the ``engine-zonos`` dir with the stdlib only::

    python -m unittest discover -s tests
"""

import os
import sys
import unittest

# Make both ``scripts`` and ``zonos_engine`` importable when run from the engine-zonos dir (the
# script imports voice_sources, which imports zonos_engine.voices).
_ENGINE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, _ENGINE_DIR)
sys.path.insert(0, os.path.join(_ENGINE_DIR, "scripts"))

import generate_reference_voices as gen  # noqa: E402


class LauncherCommandTest(unittest.TestCase):
    def test_unix_launcher_spawns_directly(self):
        # A unix launcher (no batch suffix) is spawned as-is: launcher + args, no cmd wrapper.
        cmd = gen.launcher_command("/abs/engine-image/kokoro-engine", "--stdio")
        self.assertEqual(cmd, ["/abs/engine-image/kokoro-engine", "--stdio"])

    def test_bat_launcher_wrapped_in_cmd(self):
        # A .bat launcher must be run through the command interpreter via `/c`.
        cmd = gen.launcher_command(r"C:\img\kokoro-engine.bat", "--stdio")
        self.assertEqual(cmd[1:], ["/c", r"C:\img\kokoro-engine.bat", "--stdio"])
        self.assertTrue(cmd[0].lower().endswith(("cmd.exe", "cmd")))

    def test_cmd_launcher_wrapped_in_cmd(self):
        # .cmd is treated like .bat.
        cmd = gen.launcher_command(r"C:\img\kokoro-engine.cmd", "--stdio")
        self.assertEqual(cmd[1:], ["/c", r"C:\img\kokoro-engine.cmd", "--stdio"])
        self.assertTrue(cmd[0].lower().endswith(("cmd.exe", "cmd")))

    def test_batch_suffix_is_case_insensitive(self):
        cmd = gen.launcher_command(r"C:\img\KOKORO-ENGINE.BAT", "--stdio")
        self.assertEqual(cmd[1], "/c")

    def test_comspec_is_honoured_when_set(self):
        prev = os.environ.get("COMSPEC")
        os.environ["COMSPEC"] = r"D:\Windows\system32\cmd.exe"
        try:
            cmd = gen.launcher_command(r"C:\img\kokoro-engine.bat", "--stdio")
            self.assertEqual(cmd[0], r"D:\Windows\system32\cmd.exe")
        finally:
            if prev is None:
                del os.environ["COMSPEC"]
            else:
                os.environ["COMSPEC"] = prev


if __name__ == "__main__":
    unittest.main()
