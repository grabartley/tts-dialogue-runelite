#!/usr/bin/env python3
"""Generate ``zonos-engine-manifest.json`` from the built+checksummed Zonos bundles.

The Zonos sibling of ``engine/scripts/generate_manifest.py``. The Zonos release workflow runs it
after the Zonos bundle(s) are built, checksummed, and uploaded to a GitHub Release. It produces the
small JSON resource the plugin's ``EngineInstaller`` reads (via the ``ZONOS_MANIFEST_RESOURCE``
constant) to resolve the per-OS download URL, verify the sha256, and pin the version. The shape is
byte-identical to the Kokoro manifest so ``EngineInstaller`` consumes it unchanged.

Differences from the Kokoro generator:

* ``engine`` is ``zonos`` and the launcher base name is ``zonos-engine`` (``zonos-engine.bat`` on
  Windows), matching the bundle ``build_bundle.py`` produces.
* Only platforms that actually have a built bundle are emitted. Zonos targets CUDA/NVIDIA, so v1
  ships ``win-x64`` (and optionally ``linux-x64``); the macOS slots are intentionally absent because
  Zonos has no CUDA target there, and the plugin's ``LocalZonosBackend`` then correctly stays
  unavailable and falls back to Kokoro on those platforms.
* The manifest preserves the full four-platform key set as empty placeholders for any platform
  without a bundle, so the committed resource keeps a stable shape (and a clean machine on an
  unsupported platform reads an empty url/sha => "nothing to install").

Each per-platform input dir contains:
  <platform>/<bundle-file>           the release artifact (.zip)
  <platform>/<bundle-file>.sha256    its sha256 (first whitespace token used)
  <platform>/signed                  optional marker file; presence => "signed": true

Usage:
  generate_zonos_manifest.py \
    --version v0.1.0 \
    --repo grabartley/tts-dialogue-runelite \
    --artifacts-dir ./release-artifacts \
    --out ./src/main/resources/zonos-engine-manifest.json
"""
import argparse
import json
import os
import sys

# The committed resource keeps all four keys; Zonos only populates the CUDA-capable ones.
ALL_PLATFORMS = ["osx-aarch64", "osx-x64", "linux-x64", "win-x64"]


def launcher_for(platform):
    return "zonos-engine.bat" if platform.startswith("win") else "zonos-engine"


def empty_entry(platform):
    return {
        "url": "",
        "sha256": "",
        "size": 0,
        "signed": False,
        "launcher": launcher_for(platform),
    }


def read_sha256(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read().strip().split()[0]


def find_bundle(platform_dir):
    for name in sorted(os.listdir(platform_dir)):
        if name.endswith(".sha256") or name == "signed":
            continue
        full = os.path.join(platform_dir, name)
        if os.path.isfile(full):
            return name, full
    raise FileNotFoundError("No bundle file found in {}".format(platform_dir))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--repo", required=True, help="owner/name for the Releases download URL")
    ap.add_argument("--artifacts-dir", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    artifacts = {}
    built = 0
    for platform in ALL_PLATFORMS:
        pdir = os.path.join(args.artifacts_dir, platform)
        if not os.path.isdir(pdir):
            # Keep a stable, empty placeholder for platforms with no Zonos bundle (e.g. macOS).
            artifacts[platform] = empty_entry(platform)
            continue
        bundle_name, bundle_path = find_bundle(pdir)
        sha = read_sha256(bundle_path + ".sha256")
        url = "https://github.com/{}/releases/download/{}/{}".format(
            args.repo, args.version, bundle_name
        )
        artifacts[platform] = {
            "url": url,
            "sha256": sha,
            "size": os.path.getsize(bundle_path),
            "signed": os.path.isfile(os.path.join(pdir, "signed")),
            "launcher": launcher_for(platform),
        }
        built += 1

    manifest = {
        "schemaVersion": 1,
        "engine": "zonos",
        "version": args.version,
        "warning": None,
        "artifacts": artifacts,
    }

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print("Wrote {} with {} populated platform(s)".format(args.out, built))


if __name__ == "__main__":
    main()
