"""Allow ``python -m zonos_engine ...`` to drive the same entry point the bundle launcher uses."""

from .engine import main

if __name__ == "__main__":
    raise SystemExit(main())
