"""Stdlib-only tests for ``generate_zonos_manifest.py``.

These run on ANY machine: they import only the manifest generator (stdlib + that module) and stage a
fake ``release-artifacts`` tree on disk, never building a real bundle. Their job is to prove the
generator emits BOTH manifest shapes correctly (issue #60):

* a single-file platform entry (``url``/``sha256``/``size``/``launcher``) when the platform dir holds
  a plain ``.zip`` + ``.sha256``, and
* a split platform entry (ordered ``parts`` list + combined ``sha256``) when the dir holds ordered
  ``<archive>.zip.partNN`` files + per-part and combined ``.sha256`` sidecars.

It also confirms platforms with no bundle dir stay empty placeholders.

Run from the ``engine-zonos`` dir with the stdlib only::

    python -m unittest discover -s tests
"""

import json
import os
import sys
import tempfile
import unittest

_ENGINE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ENGINE_DIR, "packaging"))

import generate_zonos_manifest as gen  # noqa: E402


def _write(path, data):
    with open(path, "wb") as f:
        f.write(data)


def _write_sha(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text + "\n")


class GenerateZonosManifestTest(unittest.TestCase):
    def _run(self, artifacts_dir):
        out = os.path.join(artifacts_dir, "manifest.json")
        argv = [
            "generate_zonos_manifest.py",
            "--version",
            "v9.9.9",
            "--repo",
            "grabartley/tts-dialogue-runelite",
            "--artifacts-dir",
            artifacts_dir,
            "--out",
            out,
        ]
        old = sys.argv
        sys.argv = argv
        try:
            gen.main()
        finally:
            sys.argv = old
        with open(out, "r", encoding="utf-8") as f:
            return json.load(f)

    def test_split_platform_emits_ordered_parts_and_combined_sha(self):
        with tempfile.TemporaryDirectory() as d:
            pdir = os.path.join(d, "win-x64")
            os.makedirs(pdir)
            archive = "zonos-engine-v9.9.9-win-x64.zip"
            # Two ordered parts, out-of-order on disk to prove ordinal sorting, + combined sha.
            _write(os.path.join(pdir, archive + ".part01"), b"BBBB")
            _write_sha(os.path.join(pdir, archive + ".part01.sha256"), "sha-part01")
            _write(os.path.join(pdir, archive + ".part00"), b"AAA")
            _write_sha(os.path.join(pdir, archive + ".part00.sha256"), "sha-part00")
            _write_sha(os.path.join(pdir, archive + ".sha256"), "combined-sha")

            manifest = self._run(d)
            entry = manifest["artifacts"]["win-x64"]

            self.assertIn("parts", entry)
            self.assertEqual(entry["archive"], archive)
            self.assertEqual(entry["sha256"], "combined-sha")
            self.assertEqual(entry["size"], 7)  # 3 + 4 bytes
            self.assertEqual(entry["launcher"], "zonos-engine.bat")
            # Parts are listed in reassembly order regardless of on-disk listing order.
            self.assertEqual([p["sha256"] for p in entry["parts"]], ["sha-part00", "sha-part01"])
            self.assertEqual(
                [os.path.basename(p["url"]) for p in entry["parts"]],
                [archive + ".part00", archive + ".part01"],
            )
            self.assertEqual([p["size"] for p in entry["parts"]], [3, 4])
            self.assertTrue(
                entry["parts"][0]["url"].startswith(
                    "https://github.com/grabartley/tts-dialogue-runelite/releases/download/v9.9.9/"
                )
            )

    def test_single_file_platform_keeps_flat_shape(self):
        with tempfile.TemporaryDirectory() as d:
            pdir = os.path.join(d, "linux-x64")
            os.makedirs(pdir)
            bundle = "zonos-engine-v9.9.9-linux-x64.zip"
            _write(os.path.join(pdir, bundle), b"ZIPDATA!")
            _write_sha(os.path.join(pdir, bundle + ".sha256"), "flat-sha")

            entry = self._run(d)["artifacts"]["linux-x64"]
            self.assertNotIn("parts", entry)
            self.assertEqual(entry["sha256"], "flat-sha")
            self.assertEqual(entry["size"], 8)
            self.assertEqual(os.path.basename(entry["url"]), bundle)
            self.assertEqual(entry["launcher"], "zonos-engine")

    def test_missing_platform_dirs_stay_empty_placeholders(self):
        with tempfile.TemporaryDirectory() as d:
            manifest = self._run(d)
            for platform in gen.ALL_PLATFORMS:
                entry = manifest["artifacts"][platform]
                self.assertEqual(entry["url"], "")
                self.assertEqual(entry["sha256"], "")
                self.assertNotIn("parts", entry)


if __name__ == "__main__":
    unittest.main()
