#!/usr/bin/env python3
"""Cheap frozen-import probe for the Zonos engine's torch/numpy load path.

WHY THIS EXISTS
---------------
The frozen Zonos engine once reported no GPU with
``torch/CUDA unavailable: cannot load module more than once per process``. That string is numpy
2.4+'s guard against a native module initialising twice in one process. It tripped because
PyInstaller's bootstrap removes numpy from ``sys.modules`` in ``cleanup_loaded_modules`` and torch
then re-imports it, so numpy's C extension ran its module-init a second time. The fix is to pin
``numpy<2.4`` (issue #77 / the numpy pin in packaging/requirements.txt).

A full release build (~50 min, ~2.9 GB) is far too expensive to use as the feedback loop for that
one-line pin. This script is the cheap reproduction: it does exactly what the engine's GPU probe
does at import time -- ``import torch``, then ``torch.cuda.is_available()`` -- and nothing else. When
PyInstaller-frozen and run, it exercises the same ``cleanup_loaded_modules`` + numpy re-import path
that broke the real engine, so a clean exit here means the frozen ``import torch`` works with the
pinned numpy.

Note ``torch.cuda.is_available()`` returning False on a GPU-less CI runner is EXPECTED and fine: the
double-load failure happens at IMPORT, before any CUDA call, so we are validating the import, not the
GPU. We only fail if importing torch raises (the double-load manifests as an ImportError/RuntimeError
whose message contains "cannot load module more than once per process").
"""

from __future__ import annotations

import sys
import traceback


def main() -> int:
    # Import numpy first only to report its version; the real test is the torch import below, which
    # is where the frozen bootstrap's numpy re-import (and the 2.4+ guard) actually fires.
    try:
        import numpy

        print("numpy version: {}".format(numpy.__version__), flush=True)
    except Exception:  # noqa: BLE001 - surface but do not mask the torch import test
        print("numpy import failed (continuing to the torch import test):", flush=True)
        traceback.print_exc()

    # THE test: import torch exactly as the engine does (engine-zonos/zonos_engine/synthesizer.py's
    # _import_torch -> `import torch`). In the frozen build this is where the double-load surfaced.
    try:
        import torch
    except Exception:  # noqa: BLE001 - any import failure is a smoke-test FAILURE
        print("FAILURE: `import torch` raised:", flush=True)
        traceback.print_exc()
        return 1

    print("torch version: {}".format(torch.__version__), flush=True)

    # Run the same CUDA probe the engine's cuda_available() runs. False on a GPU-less runner is FINE;
    # we only care that calling it does not raise (it would, under a double-loaded extension).
    try:
        available = bool(torch.cuda.is_available())
        print("torch.cuda.is_available(): {}".format(available), flush=True)
    except Exception:  # noqa: BLE001 - a raising probe is also a failure of the import path
        print("FAILURE: torch.cuda.is_available() raised:", flush=True)
        traceback.print_exc()
        return 1

    print("SMOKE OK: frozen `import torch` + cuda probe completed without a double-load.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
