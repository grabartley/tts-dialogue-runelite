# Engine Pipeline

How the plugin jar and the external TTS engine relate, how each is built and shipped, and the runbook for cutting an engine release. The `README.md` covers what the plugin does for a player; this doc covers the build/release plumbing for maintainers and contributors.

## Two artifacts, two channels

The project ships two separate artifacts on two separate channels.

| Artifact | What it is | Built/tested by | Published by |
|----------|------------|-----------------|--------------|
| **Plugin jar** | Tiny, pure-JVM RuneLite plugin. Ships with no engine binary and no voice model. | `.github/workflows/cicd.yml` on every PR | The **RuneLite Plugin Hub**, which builds it from source at a tagged commit. This repo does not self-publish the jar. |
| **Engine bundles** | Self-contained per-OS engine processes (jlink runtime + native libs + model + licenses). | `.github/workflows/engine-release.yml` (also compiled in `cicd.yml`) | This repo's **GitHub Releases**, via the manual `engine-release.yml` workflow. |

The jar is what a user installs from the Hub. The engine bundle is what the jar downloads at runtime. They are versioned and released independently, and the manifest below is the contract that binds a given jar build to a specific published engine release.

There are **two** engine families behind the same transport, each with its own bundle, manifest, and release workflow:

| Engine | Backend | Runtime | Bundle contents | Built by | Manifest |
|--------|---------|---------|-----------------|----------|----------|
| **Kokoro** (CPU) | `local-kokoro` (default `LOCAL`) | jlink JVM + sherpa-onnx native libs (ONNX) | engine jars, native libs, jlink runtime, Kokoro model, licenses | `engine-release.yml` (`engine/` Gradle module) | `engine-manifest.json` |
| **Zonos** (GPU) | `local-zonos` (`LOCAL_GPU`) | embedded Python + PyTorch CUDA wheels (PyTorch model) | PyInstaller-frozen Python runtime, torch CUDA wheels (bundled CUDA runtime), Zonos package + weights, reference-voice bank, licenses | `zonos-engine-release.yml` (`engine-zonos/` Python dir) | `zonos-engine-manifest.json` |

Both speak the identical `--stdio` wire protocol (`ExternalEngineClient` drives either), so the plugin transport is shared. They differ only in language/runtime: Kokoro is a JVM engine in the `engine/` Gradle subproject; Zonos is a standalone Python engine in the top-level `engine-zonos/` directory, which is deliberately **outside** the Gradle build (not in `settings.gradle`, not a source set), so `./gradlew build` never compiles or is affected by it.

## CI vs release

These are two distinct workflows with non-overlapping responsibilities.

### `cicd.yml` — the PR gate (build + test only)

- Triggers: `on: pull_request` (to `main`) and `workflow_dispatch` (a manual re-run of the same gate, never a release).
- Validates the Gradle wrapper, runs `./gradlew spotlessCheck`, then `./gradlew build`, which compiles the plugin **and** the `:engine` module and runs the full test suite. That includes the engine's `--stdio` framing/manifest conformance tests (`EngineConformanceTest`).
- Publishes the JUnit report and uploads the built plugin jar as a CI artifact.
- It deliberately **never** builds the cross-platform engine bundles, signs, notarizes, or publishes anything.

### `engine-release.yml` — the manual release (build bundles + sign + publish)

- Trigger: `on: workflow_dispatch` **only**. There is intentionally no `push`/tag trigger. Inputs are a `version` tag string (e.g. `v1.0.0`) and a `prerelease` boolean flag (defaults to `true`).
- A matrix `build` job produces the three Kokoro bundles, each on a runner matching its OS/arch so the jlink runtime and native libs are native to the target:

  | Target | Runner | Archive |
  |--------|--------|---------|
  | `linux-x64` | `ubuntu-latest` | `.tar.gz` |
  | `win-x64` | `windows-latest` | `.zip` |
  | `osx-aarch64` | `macos-14` | `.tar.gz` |

  Each bundle carries the engine application jar, the per-target sherpa-onnx native libraries, a self-contained `jlink` Java runtime (so end users need no JDK), the Kokoro model (`kokoro-multi-lang-v1_0`, downloaded from the sherpa-onnx model release and normalized to a `model/` dir), and the Apache-2.0 attribution files under `licenses/`.
- Per-target validation runs before any signing: the `--stdio` conformance test runs on the Linux bundle (`EngineConformanceTest`, asserting the full frame round-trips on a real built engine), and every target runs a native `--selftest` (synthesize a fixed phrase, report sample rate + sample count). Self-test runs before signing so a self-test failure fast-fails cheaply and a signed bundle always implies "passed self-test".
- Optional, secret-gated code-signing/notarization (see the table below). With no secrets the bundles ship **unsigned** and the workflow still completes.
- Each bundle is sha256'd. A `publish` job gathers all targets, regenerates `engine-manifest.json` via `engine/scripts/generate_manifest.py`, publishes (or refreshes) a GitHub Release under the version tag with the bundles + their `.sha256` files, and opens an auto-PR to commit the refreshed manifest into the plugin resources.
- The workflow is re-runnable: re-running for the same tag refreshes that release's assets.

### `zonos-engine-release.yml` — the manual Zonos GPU release (self-contained bundle)

The Zonos GPU engine has its own manual release workflow, mirroring the Kokoro one but producing a Python bundle instead of a JVM one.

- Trigger: `on: workflow_dispatch` **only**, same as Kokoro. Inputs: a `version` tag, a `prerelease` flag, a `zonos_ref` (Zonos upstream git sha/tag to pin), a `torch_index` (the PyTorch CUDA wheel index, default `cu124`), and a `skip_weights` flag (embed weights vs. fetch on first run).
- A matrix `build` job produces the Zonos bundle(s). v1 targets **`win-x64` on `windows-latest`** (the primary gaming-PC target); `linux-x64` is wired in the matrix behind a comment for a later follow-up. macOS is intentionally absent because Zonos targets CUDA/NVIDIA, so the plugin's `LocalZonosBackend` stays unavailable there and falls back to Kokoro.
- **Packaging needs no GPU.** The bundle is assembled by `engine-zonos/packaging/build_bundle.py` on a standard Windows runner: it creates a clean venv, installs the PyTorch **CUDA wheels** (which carry their own CUDA runtime) + Zonos + phonemizer, downloads the Zonos-v0.1 weights into `model/`, asserts the reference-voice bank under `voices/` is complete for every id the plugin's `ZonosVoiceMap` can emit, then runs **PyInstaller** (`packaging/zonos-engine.spec`) to freeze the engine + its whole dependency graph (embedded interpreter + torch CUDA + Zonos) into `runtime/`. The result is laid out as `zonos-engine(.bat)` launcher + `runtime/` + `voices/` + `model/` + `licenses/` and zipped. Only **running** the bundle's real synthesis needs an NVIDIA GPU, which is the user's machine; CI cannot run real synthesis.
- Self-contained means: on a clean machine with only the NVIDIA driver — no system Python, no CUDA toolkit, no dev environment — the bundle's `zonos-engine --selftest` synthesizes on the GPU. The PyTorch CUDA wheels' bundled CUDA runtime is why the CUDA toolkit is not required.
- **The bundle is split for release.** The self-contained Zonos `.zip` is ~2.97 GB, over GitHub's 2 GiB per-file release-asset cap (a single-asset upload returns HTTP 422). So after zipping, the workflow computes the sha256 of the full reassembled `.zip`, then `split -b 1900m -d -a 2` slices it into ordered parts `…zip.part00`, `…zip.part01`, … (1900 MiB leaves margin under the cap). Each part is sha256'd, the original `.zip` is dropped, and the parts + per-part `.sha256` + the combined `.zip.sha256` are uploaded. Kokoro stays single-file (well under the cap).
- A `publish` job gathers the targets, regenerates `zonos-engine-manifest.json` via `engine-zonos/packaging/generate_zonos_manifest.py` (the Zonos sibling of the Kokoro generator: `engine: "zonos"`, `zonos-engine` launchers, only CUDA-capable platforms populated, macOS slots left as empty placeholders; it detects the `…zip.partNN` files and emits the split `parts` shape below), publishes (or refreshes) a GitHub Release under the `zonos-<version>` tag, and opens an auto-PR to commit the refreshed manifest.
- The reference-voice clips under `voices/` are generated audio assets kept out of source control: the release workflow builds the Kokoro CPU engine and runs `engine-zonos/scripts/generate_reference_voices.py` to synthesize one `<id>.wav` per Zonos voice id (from the Kokoro speaker for the same race/gender/player) before the build, and `build_bundle.py` fails loudly if any required `<id>.wav` is missing rather than shipping a bundle that cannot voice every race/gender.

## The manifest is the glue

The plugin jar ships tiny: no engine binary, no voice model. `src/main/resources/engine-manifest.json` is the small JSON resource that binds the jar to a published engine release. Its shape is flat and stable: a `version`, an `engine` name, and an `artifacts` map keyed by platform id (`osx-aarch64`, `linux-x64`, `win-x64`), each entry carrying `url`, `sha256`, `size`, `signed`, and `launcher`.

A platform entry is one of two shapes, distinguished unambiguously by the presence of a `parts` array:

| Shape | Fields | Used by |
| --- | --- | --- |
| **Single-file** | `url`, `sha256`, `size`, `signed`, `launcher` | Kokoro, and any bundle under the 2 GiB asset cap |
| **Split** | `archive`, `sha256` (of the reassembled archive), `size` (total), `signed`, `launcher`, `parts: [{url, sha256, size}, …]` | the oversize Zonos GPU bundle (issue #60) |

At runtime `EngineInstaller` (`src/main/java/com/grahambartley/synthesis/engine/EngineInstaller.java`) reads the bundled manifest via `getResourceAsStream` and resolves the entry for the current OS/arch. For a **single-file** entry it downloads the bundle from its `url`, verifies the `sha256`, and extracts it under `~/.runelite/tts-dialogue/engines/<engine>-<version>/`. For a **split** entry it downloads each part via the injected `OkHttpClient`, verifies each part's `sha256`, concatenates the parts **in order** into the final `.zip` (streamed to a temp file), verifies the combined `sha256`, then runs the same extraction path and deletes the part files. On macOS it then clears the `com.apple.quarantine` extended attribute on the extracted files so Gatekeeper does not block an unsigned engine. The plugin runs the extracted launcher as the external `--stdio` process and reuses it on later runs (the install is idempotent: an already-extracted launcher is reused without re-downloading). Any missing/short/corrupt part or hash mismatch fails cleanly to "backend unavailable" with no partial install — never a crash, never a game-thread block.

If the manifest entry has an empty `url`/`sha256` (the dev placeholder), the installer treats it as "no engine published yet" — not an error, just nothing to install. So merging the auto-generated manifest after a release is what flips the plugin from "no engine" to "downloads the real bundle".

The same installer also resolves a second engine through `zonos-engine-manifest.json` (the Zonos GPU backend) using the same mechanism, including the split-parts reassembly above.

## Release runbook (correct order)

Releases are **never automatic**. Cut one in this order:

1. **Dispatch `engine-release.yml`** from the Actions tab ("Engine Release" -> "Run workflow"), supplying the `version` tag and the `prerelease` flag. It builds and validates the three Kokoro bundles, signs them if secrets are present, publishes the GitHub Release, regenerates `engine-manifest.json`, and opens an auto-PR with the updated manifest.
2. **Merge the manifest auto-PR** so the bundled manifest in the plugin points at the real, published engine bundles (real `url` + `sha256` per platform) instead of the dev placeholders.
3. **Submit/update the plugin in `runelite/plugin-hub`** at the tagged commit (see issue #31) so the Hub builds the jar from source at that commit and serves it. The jar is published by the Hub, not by this repo.
4. **Users install from the Hub.** On first use of the local voice, the jar reads the merged manifest, downloads the per-OS engine bundle from the Release, verifies its sha256, and runs it.

### Zonos GPU release runbook

Cutting a Zonos GPU engine release follows the same shape as Kokoro, on its own workflow:

1. **(Optional, CI does this automatically) Generate the reference-voice bank.** The clips are generated from the Kokoro CPU engine, not staged by hand: the release workflow builds the Kokoro engine image, downloads the model, and runs `engine-zonos/scripts/generate_reference_voices.py` to populate `engine-zonos/voices/` before packaging. To produce the bank locally instead, build the Kokoro image (`./gradlew :engine:engineImage`), stage the `kokoro-multi-lang-v1_0` model into `engine/build/engine-image/model/`, then run `python engine-zonos/scripts/generate_reference_voices.py --engine-launcher engine/build/engine-image/kokoro-engine`. `build_bundle.py` asserts the bank is complete and fails the build otherwise. The clips are gitignored generated assets (Kokoro-generated, Apache-2.0; see `engine-zonos/voices/ATTRIBUTION.md`).
2. **Dispatch `zonos-engine-release.yml`** from the Actions tab ("Zonos Engine Release" -> "Run workflow"), supplying the `version` tag (and optionally pinning `zonos_ref`). It builds the Kokoro engine and generates the reference-voice bank, then builds the `win-x64` Zonos bundle on a standard Windows runner (no GPU), sha256s the reassembled `.zip`, splits it into sub-2 GiB `…zip.partNN` parts (each sha256'd), publishes the GitHub Release under the `zonos-<version>` tag with all parts + checksums, regenerates `zonos-engine-manifest.json` with the `parts` shape, and opens an auto-PR with the updated manifest.
3. **Validate the bundle on a GPU box** (the standalone runbook below) before merging the manifest, since CI cannot run real synthesis.
4. **Merge the manifest auto-PR** so the plugin's `zonos-engine-manifest.json` points at the real `win-x64` bundle (real `parts` list + combined `sha256`) instead of the dev placeholder. That flips the `LOCAL_GPU` backend from "unavailable, falls back to Kokoro" to "downloads, reassembles, and runs the real Zonos bundle."

### Standalone GPU validation runbook (no game needed)

The Zonos bundle is validated on a GPU machine **without RuneScape**, so engine validation carries no game-account exposure. On a clean Windows x64 box with only a compatible NVIDIA driver (no Python, no CUDA toolkit):

1. Get the bundle: either dispatch `zonos-engine-release.yml` and download the `win-x64` asset from the resulting `zonos-<version>` Release, or build it locally on a Windows machine with `python engine-zonos/packaging/build_bundle.py --platform win-x64 --version vX.Y.Z`.
2. Unzip it anywhere. The launcher `zonos-engine.bat` sits at the archive root next to `runtime/`, `voices/`, `model/`, and `licenses/`.
3. Run `zonos-engine.bat --selftest`. It loads Zonos-v0.1 on the GPU, synthesizes a fixed phrase, and prints `gpu=true`, the GPU detail, and `sampleRate=44100 samples=<N>`. Add `--wav out.wav` to write a listenable file.
4. **Listen** to `out.wav` to confirm the GPU produced real, intelligible speech. To hear emotion render, the engine's `--selftest` uses a HAPPY preset; the five emotions are exercised end-to-end through the plugin once the manifest is merged.

If `--selftest` prints `gpu=false`, the box has no usable CUDA device (or the driver is too old); that is exactly the case where the plugin's `LocalZonosBackend` reports unavailable and falls back to Kokoro.

### Validating the wire protocol without a GPU

The `--stdio` framing is proven without torch or a GPU, so it can run in CI and on any dev machine:

- `engine-zonos/tests/test_protocol.py` (stdlib-only `unittest`) decodes plugin-shaped request lines, asserts the header bytes and little-endian float32 PCM frame match exactly, exercises the `{ok, gpu}` health line, and drives the real `--stdio` loop with a torch-free **mock** synthesizer. Run it with `python -m unittest discover -s tests` from `engine-zonos/` — no pip install.
- `zonos-engine --mock --stdio` / `zonos-engine --mock --selftest` run the same engine loop with the mock tone generator, so the request/response framing and health handshake can be smoke-tested on a machine with no GPU. The mock always reports `gpu=false` and is never the shipped synthesis path.

What this **cannot** prove locally: the real Zonos synthesis quality, the emotion-vector audibly distinguishing the five emotions, and the "self-contained on a clean Windows box with only a driver" claim. Those run only on the Windows CI runner (the bundle build) and on a user's GPU (the actual synthesis), and are covered by the standalone runbook above. This is the open spike risk for the Zonos engine.

### Signing secrets

Signing is optional. Absent the secrets, the pipeline still completes and publishes **unsigned** bundles, and the macOS first-run fallback below covers Gatekeeper. Configure these repository secrets to enable signing/notarization:

| Secret(s) | Enables |
|-----------|---------|
| `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD`, `MACOS_CERT_P12`, `MACOS_CERT_PASSWORD` | macOS code-signing + notarization. Codesign is gated on `APPLE_TEAM_ID` + `MACOS_CERT_P12`; notarization additionally needs `APPLE_ID` + `APPLE_APP_PASSWORD`. A bundle is only marked `"signed": true` in the manifest once both codesign and notarize succeed. |
| `WINDOWS_CERT_PFX`, `WINDOWS_CERT_PASSWORD` | Windows Authenticode signing (`signtool`). |

### macOS first-run fallback (unsigned bundles)

If a macOS bundle is unsigned and un-notarized, Gatekeeper blocks it on first launch. Two paths recover it:

- The plugin's `EngineInstaller` clears the `com.apple.quarantine` extended attribute on the extracted engine after download, which covers the common case automatically.
- If a launch is still blocked, **right-click the `kokoro-engine` launcher in Finder and choose Open**, then confirm. macOS remembers the approval for later runs.

Windows SmartScreen may warn on an unsigned bundle; choose **More info -> Run anyway**.

## Versioning

The plugin and engine versions are **decoupled**. The manifest is the contract between them: a given plugin build points at exactly one engine release through the bundled manifest. When the engine or its `--stdio` protocol changes, bump the engine version, re-run `engine-release.yml`, merge the new manifest, and re-tag the plugin commit submitted to the Hub. A plugin-only change that does not touch the protocol can ship against the existing engine release without cutting a new one.

## Current status / gaps

State as of now, so the runbook is not read as already-done:

- **No engine Release exists yet.** `engine-release.yml` has not been run, so there is no published GitHub Release and the bundled `engine-manifest.json` still ships the dev placeholder (empty `url`/`sha256`). The installer correctly treats that as "no engine to install." Step 1 of the runbook is the first thing that produces a real engine.
- **No Zonos release exists yet, though the pipeline is in place.** The Zonos GPU engine (`engine-zonos/`), its self-contained bundle packaging (`packaging/build_bundle.py` + `zonos-engine.spec`), its manual `zonos-engine-release.yml` workflow, and the reference-voice bank generator (`engine-zonos/scripts/generate_reference_voices.py`, which synthesizes the clips from the Kokoro CPU engine) are all implemented and the wire protocol is framing-validated. The reference-voice bank is no longer a manual gap: the release workflow generates it from Kokoro before packaging, and it is fully reproducible locally on a CPU. What is still missing before the `LOCAL_GPU` backend works end to end is an actual dispatched Zonos release (the bundle build + real GPU synthesis still need Windows/NVIDIA). `zonos-engine-manifest.json` therefore remains the dev placeholder (empty `url`/`sha256`), so the Local (GPU) Zonos backend stays unavailable at runtime and selecting it falls back to the local Kokoro voice until a Zonos release is cut and its manifest merged.
