# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for the cheap torch/numpy frozen-import smoke test (smoke_import.py).

This mirrors the torch-handling half of the real engine spec (zonos-engine.spec) so the FROZEN
import path is faithful to production, but stays minimal: it freezes ONLY smoke_import.py and torch
(plus numpy, pulled in transitively by torch). It deliberately does NOT collect zonos / phonemizer /
the engine package, because the bug under test -- numpy 2.4+'s "cannot load module more than once per
process" raised when PyInstaller's bootstrap removes numpy and torch re-imports it -- happens purely
on the ``import torch`` path and needs none of those heavyweight deps. Skipping them is what makes the
smoke test cheap.

CRITICAL (issue #77): torch is NOT listed in hiddenimports and is NOT passed to collect_all here.
PyInstaller's built-in torch hook already collects torch's compiled C-extensions (.pyd/.so) and the
bundled CUDA runtime exactly once. Doing collect_all("torch") on top of that hook bundles torch's
native extension under two resolvable paths and is itself a trigger for the double-load. We take only
torch's non-binary data files + dist metadata (exactly as the real spec does) and let the hook own the
binaries. torch is pulled in because smoke_import.py imports it, so its hook fires regardless.
"""

import os

block_cipher = None

HERE = os.path.abspath(os.getcwd())
ENTRY = os.path.join(HERE, "packaging", "smoke_import.py")

hiddenimports = []
datas = []
binaries = []

try:
    from PyInstaller.utils.hooks import collect_data_files, copy_metadata

    # torch: ONLY non-binary data files + dist metadata, never its binaries (the hook owns those).
    # This is the same collection the real engine spec uses for torch, so the frozen import path the
    # smoke test exercises matches production. numpy travels with torch via the same mechanism.
    for pkg in ("torch",):
        datas += collect_data_files(pkg)
        try:
            datas += copy_metadata(pkg)
        except Exception:  # pragma: no cover - metadata is optional
            pass
except Exception:  # pragma: no cover - only exercised inside a PyInstaller run
    pass


a = Analysis(
    [ENTRY],
    pathex=[HERE],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib"],
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="smoke-import",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    name="smoke-import",
)
