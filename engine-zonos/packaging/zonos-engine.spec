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
# Freeze the top-level runner, NOT zonos_engine/engine.py directly: PyInstaller runs the entry as
# __main__ with no package context, so freezing engine.py directly breaks its package-relative
# imports ("attempted relative import with no known parent package"). zonos_engine_cli.py imports
# the package by absolute name, so the package's internal relative imports resolve in the bundle.
ENTRY = os.path.join(HERE, "zonos_engine_cli.py")

# Collect the data/metadata torch + zonos + phonemizer need at runtime. These hidden imports and
# collected packages are what make the frozen exe actually self-contained.
#
# IMPORTANT (issue #77): torch/torchaudio are NOT in hiddenimports and are NOT passed to collect_all
# below. PyInstaller ships dedicated hooks for torch and torchaudio that already collect their
# compiled C-extensions (.pyd/.so) and the bundled CUDA runtime libs exactly once. Listing torch in
# hiddenimports AND running collect_all("torch") on top of those hooks bundles torch's native
# extension under two resolvable paths; at runtime CPython then raises "cannot load module more than
# once per process" the second time the extension's module-init runs, which made the GPU probe
# report no usable GPU on a real NVIDIA box. torch is still pulled in transitively (zonos and the
# synthesizer import it), so its hook fires and its binaries travel with the exe -- just once.
hiddenimports = [
    "zonos_engine",
    "zonos_engine.protocol",
    "zonos_engine.voices",
    "zonos_engine.emotion",
    "zonos_engine.synthesizer",
    "zonos",
    "zonos.model",
    "zonos.conditioning",
    "phonemizer",
]

datas = []
binaries = []

try:
    from PyInstaller.utils.hooks import collect_all, collect_data_files, copy_metadata

    # zonos + phonemizer: collect everything (code, data, binaries) -- these have no dedicated hook
    # that would duplicate their contents, so collect_all is safe and needed for self-containment.
    for pkg in ("zonos", "phonemizer"):
        d, b, h = collect_all(pkg)
        datas += d
        binaries += b
        hiddenimports += h

    # torch/torchaudio: take ONLY non-binary data files + dist metadata, never their binaries. The
    # built-in torch/torchaudio hooks own the compiled extensions + CUDA runtime; duplicating those
    # is exactly what triggered the double-load (issue #77). collect_data_files(...) returns data
    # (e.g. version files, configs) without the .pyd/.so the hook already collects.
    for pkg in ("torch", "torchaudio"):
        datas += collect_data_files(pkg)
        try:
            datas += copy_metadata(pkg)
        except Exception:  # pragma: no cover - metadata is optional
            pass
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
