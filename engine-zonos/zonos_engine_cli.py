"""Top-level entry runner for the frozen Zonos GPU engine.

PyInstaller freezes the script it is pointed at and runs that file AS ``__main__``, with no parent
package. The engine logic lives in the ``zonos_engine`` package and uses package-relative imports
(``from . import protocol`` etc.), so the package's modules must be imported AS a package, not run
as ``__main__``. Pointing the PyInstaller ``Analysis`` at this runner (instead of
``zonos_engine/engine.py``) keeps ``zonos_engine`` a real package at runtime, so its relative
imports resolve in the frozen bundle exactly as they do under ``python -m zonos_engine``.

Keep this file dependency-light: it only imports the package and delegates to ``main``.
"""

from zonos_engine.engine import main

if __name__ == "__main__":
    raise SystemExit(main())
