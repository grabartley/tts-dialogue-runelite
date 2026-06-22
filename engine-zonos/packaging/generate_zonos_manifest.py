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

A platform entry is emitted in one of two shapes, distinguished unambiguously by the presence of a
``parts`` array:

* Single-file (Kokoro-style, and any Zonos bundle that fits under GitHub's 2 GiB asset cap)::

    {"url", "sha256", "size", "signed", "launcher"}

* Split (a Zonos bundle too large for a single asset; see issue #60). The reassembled ``.zip`` is
  uploaded as ordered ``.partNN`` files; the entry carries the ordered ``parts`` list plus the
  combined sha256 of the reassembled archive::

    {"archive", "sha256", "size", "signed", "launcher",
     "parts": [{"url", "sha256", "size"}, ...]}

  ``sha256``/``size`` describe the reassembled archive; ``archive`` is its file name. The
  installer concatenates the parts in order, verifies each part and then the combined sha256.

Each per-platform input dir contains EITHER a single ``.zip`` (single-file) OR a set of ordered
``.partNN`` files (split), with a ``.sha256`` sidecar per uploaded file (first whitespace token
used). For a split bundle the combined ``<archive>.zip.sha256`` is also present (the sha256 of the
reassembled archive). A ``signed`` marker file => ``"signed": true``.

``--version`` drives the bundle FILENAMES (e.g. ``zonos-engine-v0.1.0-win-x64.zip``) and the
manifest ``version`` field. ``--release-tag`` is the GitHub Release tag the assets are published
under and is used ONLY for the ``releases/download/<tag>/`` segment of each download URL. The Zonos
release is published under ``zonos-<version>`` (e.g. ``zonos-v0.1.0``), which differs from the bare
filename version, so the two must be passed separately. ``--release-tag`` defaults to ``--version``
when omitted so existing local invocations keep working.

Usage:
  generate_zonos_manifest.py \
    --version v0.1.0 \
    --release-tag zonos-v0.1.0 \
    --repo grabartley/tts-dialogue-runelite \
    --artifacts-dir ./release-artifacts \
    --out ./src/main/resources/zonos-engine-manifest.json
"""
import argparse
import json
import os
import re
import sys

# The committed resource keeps all four keys; Zonos only populates the CUDA-capable ones.
ALL_PLATFORMS = ["osx-aarch64", "osx-x64", "linux-x64", "win-x64"]

# Ordered split parts are named "<archive>.zip.part00", ".part01", ...; capture the archive name
# (everything up to and including ".zip") and the numeric ordinal so parts sort deterministically.
PART_RE = re.compile(r"^(?P<archive>.+\.zip)\.part(?P<ordinal>\d+)$")


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


def url_for(repo, release_tag, name):
    return "https://github.com/{}/releases/download/{}/{}".format(repo, release_tag, name)


def find_parts(platform_dir):
    """Return the ordered list of (ordinal, name) split parts in a dir, or [] if not a split bundle.

    Parts are matched by the ``<archive>.zip.partNN`` pattern and sorted by their numeric ordinal so
    the manifest lists them in reassembly order regardless of filesystem listing order.
    """
    found = []
    for name in os.listdir(platform_dir):
        m = PART_RE.match(name)
        if m and os.path.isfile(os.path.join(platform_dir, name)):
            found.append((int(m.group("ordinal")), name, m.group("archive")))
    found.sort(key=lambda t: t[0])
    return found


def find_bundle(platform_dir):
    for name in sorted(os.listdir(platform_dir)):
        if name.endswith(".sha256") or name == "signed" or PART_RE.match(name):
            continue
        full = os.path.join(platform_dir, name)
        if os.path.isfile(full):
            return name, full
    raise FileNotFoundError("No bundle file found in {}".format(platform_dir))


def split_entry(platform, platform_dir, repo, release_tag, parts, signed):
    """Build a split platform entry from the ordered parts plus the combined archive sha256.

    ``parts`` is the list returned by :func:`find_parts`. The archive name is taken from the first
    part (all parts share it); the combined sha256 is read from ``<archive>.sha256`` and the
    reassembled size is the sum of the part sizes.
    """
    archive = parts[0][2]
    combined_sha = read_sha256(os.path.join(platform_dir, archive + ".sha256"))
    part_entries = []
    total_size = 0
    for _ordinal, name, _archive in parts:
        part_path = os.path.join(platform_dir, name)
        size = os.path.getsize(part_path)
        total_size += size
        part_entries.append(
            {
                "url": url_for(repo, release_tag, name),
                "sha256": read_sha256(part_path + ".sha256"),
                "size": size,
            }
        )
    return {
        "archive": archive,
        "sha256": combined_sha,
        "size": total_size,
        "signed": signed,
        "launcher": launcher_for(platform),
        "parts": part_entries,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help="bundle filename version, e.g. v0.1.0")
    ap.add_argument(
        "--release-tag",
        default=None,
        help=(
            "GitHub Release tag the assets are published under (used only for the "
            "releases/download/<tag>/ URL segment); defaults to --version when omitted"
        ),
    )
    ap.add_argument("--repo", required=True, help="owner/name for the Releases download URL")
    ap.add_argument("--artifacts-dir", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    release_tag = args.release_tag if args.release_tag else args.version

    artifacts = {}
    built = 0
    for platform in ALL_PLATFORMS:
        pdir = os.path.join(args.artifacts_dir, platform)
        if not os.path.isdir(pdir):
            # Keep a stable, empty placeholder for platforms with no Zonos bundle (e.g. macOS).
            artifacts[platform] = empty_entry(platform)
            continue
        signed = os.path.isfile(os.path.join(pdir, "signed"))
        parts = find_parts(pdir)
        if parts:
            # Split bundle (issue #60): the .zip exceeded the 2 GiB asset cap and was uploaded as
            # ordered .partNN files. Emit the parts list + combined archive sha256.
            artifacts[platform] = split_entry(
                platform, pdir, args.repo, release_tag, parts, signed
            )
        else:
            bundle_name, bundle_path = find_bundle(pdir)
            artifacts[platform] = {
                "url": url_for(args.repo, release_tag, bundle_name),
                "sha256": read_sha256(bundle_path + ".sha256"),
                "size": os.path.getsize(bundle_path),
                "signed": signed,
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
