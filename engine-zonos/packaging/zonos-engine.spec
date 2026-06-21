# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for the self-contained Zonos GPU engine executable.

PyInstaller freezes the engine entry point plus its full dependency graph (the embedded Python
interpreter, the PyTorch CUDA wheels with their bundled CUDA runtime, the Zonos package, and the
phonemizer backend) into a single ``zonos-engine`` / ``zonos-engine.exe`` under ``dist/``. The
release workflow then lays this out under ``runtime/`` next to the launcher script, alongside the
``voices/`` reference bank, ``model/`` weights, and ``licenses/``, and zips the whole tree.

Run via ``pyinstaller packaging/zonos-engine.spec`` from the ``engine-zonos`` directory (the build
script ``packaging/build_bundle.py`` does this). ``--onedir`` (the default for a spec) is used, not
``--onefile``, so the large CUDA runtime libraries are not unpacked to a temp dir on every launch:
the plugin already extracts the bundle to a stable directory.
"""

import os

block_cipher = None

# Repo paths are relative to the engine-zonos dir PyInstaller is invoked from.
HERE = os.path.abspath(os.getcwd())
ENTRY = os.path.join(HERE, "zonos_engine", "engine.py")

# Collect the data/metadata torch + zonos + phonemizer need at runtime. These hidden imports and
# collected packages are what make the frozen exe actually self-contained.
hiddenimports = [
    "zonos_engine",
    "zonos_engine.protocol",
    "zonos_engine.voices",
    "zonos_engine.emotion",
    "zonos_engine.synthesizer",
    "zonos",
    "zonos.model",
    "zonos.conditioning",
    "torch",
    "torchaudio",
    "phonemizer",
]

# torch ships CUDA runtime .dll/.so inside its wheel; PyInstaller's torch hook collects them. We add
# explicit collection of torch/torchaudio/zonos data files so weights/configs travel with the exe.
datas = []
binaries = []

try:
    from PyInstaller.utils.hooks import collect_all

    for pkg in ("torch", "torchaudio", "zonos", "phonemizer"):
        d, b, h = collect_all(pkg)
        datas += d
        binaries += b
        hiddenimports += h
except Exception:  # pragma: no cover - only exercised inside the build env
    # collect_all is unavailable outside a PyInstaller run; the spec is still importable for linting.
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
    name="zonos-engine",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,  # stdout/stderr are the engine's protocol + log channels.
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    name="zonos-engine",
)
