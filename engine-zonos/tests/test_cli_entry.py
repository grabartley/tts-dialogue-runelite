"""Regression test for the frozen-bundle entry path of the Zonos engine.

PyInstaller freezes the script it is pointed at and RUNS that file as ``__main__`` with no parent
package. Because the engine logic lives in the ``zonos_engine`` package and uses package-relative
imports (``from . import protocol`` etc.), pointing PyInstaller at ``zonos_engine/engine.py``
directly broke the frozen bundle with ``ImportError: attempted relative import with no known parent
package``. The fix is the top-level ``zonos_engine_cli.py`` runner, which imports the package by
absolute name so its relative imports resolve.

The existing tests IMPORT the modules as a package, which never exercises the "run a file as a
script" path PyInstaller uses, so they could not catch that crash. This test LAUNCHES the entry
runner AS A SCRIPT via ``subprocess`` (the way the frozen exe invokes it) and asserts it starts
cleanly. It is stdlib-only and torch-free: it uses ``--mock`` so no GPU/torch/Zonos is touched.

Run from the ``engine-zonos`` dir with the stdlib only::

    python -m unittest discover -s tests
"""

import os
import subprocess
import sys
import unittest

_ENGINE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_RUNNER = os.path.join(_ENGINE_DIR, "zonos_engine_cli.py")


class CliEntryTest(unittest.TestCase):
    def _run(self, *args, stdin_data=None):
        """Launch the entry runner AS A SCRIPT, mimicking how the frozen PyInstaller exe invokes it.

        We deliberately do NOT put ``zonos_engine`` on ``PYTHONPATH`` via ``-m``: running the file
        path directly is what reproduces the frozen-bundle invocation. ``cwd`` is the engine-zonos
        dir so ``zonos_engine`` resolves the same way it does next to the frozen executable.
        """
        return subprocess.run(
            [sys.executable, _RUNNER, *args],
            cwd=_ENGINE_DIR,
            input=stdin_data,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=120,
        )

    def test_runner_exists(self):
        self.assertTrue(
            os.path.isfile(_RUNNER),
            "zonos_engine_cli.py entry runner must exist for the PyInstaller spec to freeze it",
        )

    def test_selftest_runs_as_script(self):
        """The crash was at import time: running the entry as a script must not raise ImportError."""
        proc = self._run("--mock", "--selftest")
        self.assertEqual(
            proc.returncode,
            0,
            "entry runner --mock --selftest failed:\nstdout={}\nstderr={}".format(
                proc.stdout, proc.stderr
            ),
        )
        # Guard against the exact frozen-bundle regression.
        self.assertNotIn("attempted relative import", proc.stderr)
        self.assertNotIn("ImportError", proc.stderr)
        # Self-test reports a positive sample rate / count on the mock path.
        self.assertIn("sampleRate=", proc.stdout)
        self.assertIn("samples=", proc.stdout)

    def test_stdio_health_handshake_as_script(self):
        """The {ok, gpu} health handshake the plugin gates on must answer over the script entry."""
        proc = self._run("--mock", "--stdio", stdin_data='{"op":"health"}\n')
        self.assertEqual(
            proc.returncode,
            0,
            "entry runner --mock --stdio failed:\nstdout={}\nstderr={}".format(
                proc.stdout, proc.stderr
            ),
        )
        self.assertNotIn("attempted relative import", proc.stderr)
        self.assertIn('"ok":true', proc.stdout)


if __name__ == "__main__":
    unittest.main()
