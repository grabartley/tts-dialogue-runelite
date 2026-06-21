#!/usr/bin/env python3
"""Generate engine-manifest.json from a set of built+checksummed engine bundles.

This is run by the manual release workflow (issue #36) after the target bundles have been
built, checksummed, and uploaded to a GitHub Release. It produces the small JSON resource the
plugin's EngineInstaller (#32) reads via getResourceAsStream to resolve the per-OS/arch download
URL, verify the sha256, and pin the version. The shape is intentionally flat and stable so #32 can
consume it unchanged.

Each per-platform input is a directory containing:
  <platform>/<bundle-file>           the release artifact (zip/tar.gz)
  <platform>/<bundle-file>.sha256    its sha256 (first whitespace-delimited token used)
  <platform>/signed                  optional marker file; presence => "signed": true

Platform ids: osx-aarch64, linux-x64, win-x64.

Only platforms with a present artifacts directory are emitted, so the manifest always matches the
set of bundles the release workflow actually built.

Usage:
  generate_manifest.py \
    --version v1.2.3 \
    --repo grabartley/tts-dialogue-runelite \
    --artifacts-dir ./release-artifacts \
    --out ./engine-manifest.json
"""
import argparse
import json
import os
import sys

PLATFORMS = ["osx-aarch64", "linux-x64", "win-x64"]


def launcher_for(platform):
    return "kokoro-engine.bat" if platform.startswith("win") else "kokoro-engine"


def read_sha256(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read().strip().split()[0]


def find_bundle(platform_dir):
    """Return the single bundle file in a platform dir (ignores .sha256 and markers)."""
    for name in sorted(os.listdir(platform_dir)):
        if name.endswith(".sha256") or name == "signed":
            continue
        full = os.path.join(platform_dir, name)
        if os.path.isfile(full):
            return name, full
    raise FileNotFoundError(f"No bundle file found in {platform_dir}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--repo", required=True, help="owner/name for the Releases download URL")
    ap.add_argument("--artifacts-dir", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    artifacts = {}
    for platform in PLATFORMS:
        pdir = os.path.join(args.artifacts_dir, platform)
        if not os.path.isdir(pdir):
            print(f"warning: no artifacts for {platform}, skipping", file=sys.stderr)
            continue
        bundle_name, bundle_path = find_bundle(pdir)
        sha_path = bundle_path + ".sha256"
        sha = read_sha256(sha_path)
        url = (
            f"https://github.com/{args.repo}/releases/download/"
            f"{args.version}/{bundle_name}"
        )
        artifacts[platform] = {
            "url": url,
            "sha256": sha,
            "size": os.path.getsize(bundle_path),
            "signed": os.path.isfile(os.path.join(pdir, "signed")),
            "launcher": launcher_for(platform),
        }

    manifest = {
        "schemaVersion": 1,
        "engine": "kokoro",
        "version": args.version,
        "warning": None,
        "artifacts": artifacts,
    }

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"Wrote {args.out} with {len(artifacts)} platform(s)")


if __name__ == "__main__":
    main()
