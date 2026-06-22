#!/usr/bin/env python3
"""Build a self-contained Zonos GPU engine bundle for one platform.

This is the single source of truth for "how a redistributable Zonos bundle is assembled." The
release workflow (``.github/workflows/zonos-engine-release.yml``) calls it on a standard Windows
runner; a developer can call it on a GPU box to validate locally. **No GPU is needed to PACKAGE** --
only to RUN the resulting bundle's real synthesis.

What it does, in order:

1. Create a clean build venv and install the runtime deps (``packaging/requirements.txt``), pulling
   the **PyTorch CUDA wheels** from the cu124 index so the bundle carries its own CUDA runtime. The
   end user then needs only an NVIDIA driver: no CUDA toolkit, no system Python.
2. Install Zonos-v0.1 from its upstream git repo at the pinned ref.
3. Fetch the Zonos-v0.1 weights into ``model/`` (so the bundle works fully offline) and assert the
   reference-voice bank under ``voices/`` is complete for every id the plugin's ``ZonosVoiceMap``
   can emit.
4. Run PyInstaller against ``packaging/zonos-engine.spec`` to freeze the engine + its whole
   dependency graph (embedded interpreter + torch CUDA + zonos + phonemizer) into ``runtime/``.
5. Assemble the final tree: the platform launcher (``zonos-engine`` / ``zonos-engine.bat``),
   ``runtime/`` (the frozen exe + libs), ``voices/``, ``model/``, and ``licenses/``.
6. Zip the tree to ``dist/<platform>/zonos-engine-<version>-<platform>.zip`` and write its
   ``.sha256``, the exact layout the manifest generator and ``EngineInstaller`` expect (the plugin
   resolves ``<launcher>`` directly inside the extracted root and unzips with ``ZipInputStream``).

The heavy steps (pip install of multi-GB CUDA wheels, weight download, PyInstaller) run only on the
CI runner. This script is intentionally not invoked by Gradle and is outside the Gradle source sets,
so it never affects the plugin's ``./gradlew build``.

Usage::

    python packaging/build_bundle.py --platform win-x64 --version v0.1.0 \
        [--torch-index https://download.pytorch.org/whl/cu124] \
        [--zonos-ref <git-sha-or-tag>] [--skip-weights]
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import threading
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE_DIR = os.path.dirname(HERE)  # engine-zonos/

ZONOS_GIT = "git+https://github.com/Zyphra/Zonos.git"
DEFAULT_ZONOS_REF = "main"  # pin to a sha/tag via --zonos-ref for reproducible bundles
DEFAULT_TORCH_INDEX = "https://download.pytorch.org/whl/cu124"
DEFAULT_MODEL_ID = "Zyphra/Zonos-v0.1-transformer"

LAUNCHERS = {
    "win-x64": "zonos-engine.bat",
    "linux-x64": "zonos-engine",
}


def run(cmd, **kwargs):
    print("+ " + " ".join(cmd), flush=True)
    subprocess.check_call(cmd, **kwargs)


def venv_python(venv_dir: str) -> str:
    if os.name == "nt":
        return os.path.join(venv_dir, "Scripts", "python.exe")
    return os.path.join(venv_dir, "bin", "python")


def build_venv(venv_dir: str, torch_index: str, zonos_ref: str) -> str:
    """Create the build venv and install runtime deps + Zonos. Returns the venv python path."""
    run([sys.executable, "-m", "venv", venv_dir])
    py = venv_python(venv_dir)
    run([py, "-m", "pip", "install", "--upgrade", "pip", "wheel", "setuptools"])
    # Install torch CUDA first from the CUDA index so the bundle carries CUDA runtime libs.
    run(
        [
            py, "-m", "pip", "install",
            "--extra-index-url", torch_index,
            "-r", os.path.join(HERE, "requirements.txt"),
        ]
    )
    # Zonos is not on PyPI; install it from git at the pinned ref.
    run([py, "-m", "pip", "install", "{}@{}".format(ZONOS_GIT, zonos_ref)])
    return py


def fetch_weights(py: str, model_dir: str, model_id: str) -> None:
    """Download the Zonos-v0.1 weights into ``model_dir`` so the bundle runs offline.

    Uses huggingface_hub (a torch/zonos transitive dep) to snapshot the model into the bundle. The
    engine sets HF_HOME at runtime to this dir, so first run does no network fetch.
    """
    os.makedirs(model_dir, exist_ok=True)
    script = (
        "from huggingface_hub import snapshot_download;"
        "snapshot_download(repo_id={!r}, local_dir={!r})".format(model_id, model_dir)
    )
    run([py, "-c", script])


def assert_voice_bank() -> None:
    """Fail the build if the reference-voice bank is missing any id the plugin can request."""
    sys.path.insert(0, ENGINE_DIR)
    from zonos_engine import voices  # noqa: E402

    voices_dir = voices.voices_dir(ENGINE_DIR)
    missing = []
    for vid in voices.all_voice_ids():
        if not os.path.isfile(os.path.join(voices_dir, vid + ".wav")):
            missing.append(vid)
    if missing:
        raise SystemExit(
            "Reference-voice bank under voices/ is incomplete; missing clips for: "
            + ", ".join(missing)
            + ". Add a <id>.wav for each before building a real bundle."
        )


def run_pyinstaller(py: str, work_dir: str, dist_dir: str) -> str:
    """Freeze the engine with PyInstaller; returns the produced runtime dir (the COLLECT output)."""
    run(
        [
            py, "-m", "PyInstaller",
            "--noconfirm",
            "--clean",
            "--workpath", work_dir,
            "--distpath", dist_dir,
            os.path.join(HERE, "zonos-engine.spec"),
        ],
        cwd=ENGINE_DIR,
    )
    return os.path.join(dist_dir, "zonos-engine")


def assemble_tree(platform: str, version: str, runtime_src: str, staging: str,
                  include_weights: bool) -> str:
    """Lay out the final bundle tree: launcher + runtime/ + voices/ + model/ + licenses/."""
    root = os.path.join(staging, "zonos-engine")
    if os.path.isdir(root):
        shutil.rmtree(root)
    os.makedirs(root)

    launcher = LAUNCHERS[platform]
    shutil.copy2(os.path.join(ENGINE_DIR, "launcher", launcher), os.path.join(root, launcher))
    if not platform.startswith("win"):
        os.chmod(os.path.join(root, launcher), 0o755)

    # The frozen interpreter + libs go under runtime/ (the launcher looks there).
    shutil.copytree(runtime_src, os.path.join(root, "runtime"))

    for sub in ("voices", "licenses"):
        src = os.path.join(ENGINE_DIR, sub)
        if os.path.isdir(src):
            shutil.copytree(src, os.path.join(root, sub))

    model_src = os.path.join(ENGINE_DIR, "model")
    if include_weights and os.path.isdir(model_src):
        shutil.copytree(model_src, os.path.join(root, "model"))

    return root


def zip_bundle(root: str, out_zip: str) -> None:
    """Zip the assembled tree so the launcher sits at the archive root (matches EngineInstaller)."""
    os.makedirs(os.path.dirname(out_zip), exist_ok=True)
    base = os.path.dirname(root)
    # Store (no compression): the bundle is dominated by the torch CUDA DLLs and the safetensors
    # weights, which are already near-incompressible (a DEFLATE pass shrinks the ~2.97 GB tree by a
    # fraction of a percent), so deflating just burns a long single-threaded CPU pass for ~zero size
    # gain. ZIP_STORED is far faster to create here and faster for EngineInstaller to extract on the
    # user's machine. allowZip64 stays on for the >4 GiB total / many-entry tree.
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_STORED, allowZip64=True) as zf:
        for dirpath, _dirs, files in os.walk(root):
            for name in files:
                full = os.path.join(dirpath, name)
                # Store paths relative to the bundle root so files land at the extract root, e.g.
                # zonos-engine.bat, runtime/..., voices/..., not under a nested zonos-engine/ dir.
                arc = os.path.relpath(full, root)
                zf.write(full, arc)


def write_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    digest = h.hexdigest()
    with open(path + ".sha256", "w", encoding="utf-8") as f:
        f.write(digest + "\n")
    return digest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--platform", required=True, choices=sorted(LAUNCHERS))
    ap.add_argument("--version", required=True, help="release tag, e.g. v0.1.0")
    ap.add_argument("--torch-index", default=DEFAULT_TORCH_INDEX)
    ap.add_argument("--zonos-ref", default=DEFAULT_ZONOS_REF)
    ap.add_argument("--model-id", default=DEFAULT_MODEL_ID)
    ap.add_argument("--skip-weights", action="store_true",
                    help="do not embed weights (engine fetches on first run); smaller bundle")
    args = ap.parse_args()

    build_root = os.path.join(ENGINE_DIR, "build")
    venv_dir = os.path.join(build_root, "venv")
    work_dir = os.path.join(build_root, "pyinstaller-work")
    pyi_dist = os.path.join(build_root, "pyinstaller-dist")
    staging = os.path.join(build_root, "staging")
    model_dir = os.path.join(ENGINE_DIR, "model")

    assert_voice_bank()

    py = build_venv(venv_dir, args.torch_index, args.zonos_ref)

    # The weight download (network-bound, ~1.6 GB into model/) and the PyInstaller freeze (CPU/disk
    # into runtime/) touch disjoint outputs, so overlap them: kick the snapshot download off on a
    # background thread and let it run while PyInstaller freezes, then join before assembling. Any
    # error in the download is re-raised on the main thread so the build still fails loudly.
    weights_error = []
    weights_thread = None
    if not args.skip_weights:

        def _fetch_weights():
            try:
                fetch_weights(py, model_dir, args.model_id)
            except BaseException as exc:  # noqa: BLE001 - surfaced on the main thread below
                weights_error.append(exc)

        weights_thread = threading.Thread(target=_fetch_weights, name="fetch-weights")
        weights_thread.start()

    runtime_src = run_pyinstaller(py, work_dir, pyi_dist)

    if weights_thread is not None:
        weights_thread.join()
        if weights_error:
            raise weights_error[0]

    root = assemble_tree(args.platform, args.version, runtime_src, staging,
                         include_weights=not args.skip_weights)

    bundle_name = "zonos-engine-{}-{}.zip".format(args.version, args.platform)
    out_zip = os.path.join(ENGINE_DIR, "dist", args.platform, bundle_name)
    zip_bundle(root, out_zip)
    digest = write_sha256(out_zip)
    print("Built {} ({})".format(out_zip, digest))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
