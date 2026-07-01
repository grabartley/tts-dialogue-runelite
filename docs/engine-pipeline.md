# Engine Pipeline

How the plugin jar and the external TTS engine relate, how each is built and shipped, and the runbook for cutting a release. The `README.md` covers what the plugin does for a player; this doc covers the build/release plumbing for maintainers and contributors.

## Two artifacts, two channels

The project produces two artifacts. They are built and released **together** under one version tag, but reach users through two channels.

| Artifact | What it is | Built/tested by | Published by |
|----------|------------|-----------------|--------------|
| **Plugin jar** | Pure-JVM RuneLite plugin. Ships with no engine binary and no voice model. The Hub builds the thin jar from source; the deploy also builds a standalone `*-all.jar` shadow jar (runnable via `VoicedDialoguePluginRunner`). | `.github/workflows/ci.yml` on every PR (thin jar + shadow jar) | The **RuneLite Plugin Hub** builds the thin jar from source at a tagged commit. Both jars are also attached to the GitHub Release (the shadow jar is the standalone/sideload artifact). |
| **Engine bundles** | Self-contained per-OS engine processes (jlink runtime + native libs + model + licenses). | `.github/workflows/release.yml` matrix on the manual deploy | This repo's **GitHub Releases**, in the same release as the jars. |

The thin jar is what a user installs from the Hub. The engine bundle is what that jar downloads at runtime. Both jars and the engine bundles ride one GitHub Release under one `v<version>` tag, and the manifest below is the contract that binds a jar build to the engine bundles in that release.

The local backend is served by a single engine family:

| Engine | Backend | Runtime | Bundle contents | Built by | Manifest |
|--------|---------|---------|-----------------|----------|----------|
| **Kokoro** (CPU) | `local-kokoro` (default `LOCAL`) | jlink JVM + sherpa-onnx native libs (ONNX) | engine jars, native libs, jlink runtime, Kokoro model, licenses | `release.yml` deploy (`engine-kokoro/` Gradle module) | `engine-manifest.json` |

The engine speaks the `--stdio` wire protocol (`ExternalEngineClient` drives it). Kokoro is a JVM engine in the `engine-kokoro/` Gradle subproject. (The emotional Cloud backend is a separate HTTP path with no external engine bundle.)

## CI vs deploy

The plugin has two workflows: `ci.yml` (the PR build/test gate) and `release.yml` (the manual deploy).

### Pull requests — the build/test gate (`ci.yml`)

- Trigger: `on: pull_request` (to `main`).
- Validates the Gradle wrapper, runs `./gradlew spotlessCheck`, then `./gradlew build`, which compiles the plugin **and** the `:engine-kokoro` module and runs the full test suite. That includes the engine's `--stdio` framing/manifest conformance tests (`EngineConformanceTest`).
- Publishes the JUnit report, then builds the standalone shadow jar (`./gradlew shadowJar`) and uploads it as a downloadable CI artifact.
- It deliberately **never** builds the cross-platform engine bundles, signs, notarizes, tags, or publishes anything.

### `workflow_dispatch` — the manual deploy (`release.yml`, one tagged release)

- Trigger: `on: workflow_dispatch` **only**. There is intentionally no `push`/tag trigger. The one input is a `release_type` (`alpha`/`beta`/`stable`, default `alpha`); `alpha`/`beta` publish a prerelease, `stable` a full release. The version is **not** a workflow input: it is read from the source-pinned version in `src/main/resources/plugin-version.txt`, the same value the Hub build uses.
- The `plugin` job reads that version (and refuses to release a `-SNAPSHOT`), runs the same Spotless + test gate, builds the thin jar (`./gradlew build`) and the shadow jar (`./gradlew shadowJar`), and exposes the version to the rest of the run. A cheap test failure here fast-fails before the expensive engine matrix runs.
- The `engine` matrix job produces the three Kokoro bundles, each on a runner matching its OS/arch so the jlink runtime and native libs are native to the target, all versioned to the same `v<version>`:

  | Target | Runner | Archive |
  |--------|--------|---------|
  | `linux-x64` | `ubuntu-latest` | `.tar.gz` |
  | `win-x64` | `windows-latest` | `.zip` |
  | `osx-aarch64` | `macos-14` | `.tar.gz` |

  Each bundle carries the engine application jar, the per-target sherpa-onnx native libraries, a self-contained `jlink` Java runtime (so end users need no JDK), the Kokoro model (`kokoro-multi-lang-v1_0`, downloaded from the sherpa-onnx model release and normalized to a `model/` dir), and the Apache-2.0 attribution files under `licenses/`.
- Per-target validation runs before any signing: the `--stdio` conformance test runs on the Linux bundle (`EngineConformanceTest`, asserting the full frame round-trips on a real built engine), and every target runs a native `--selftest` (synthesize a fixed phrase, report sample rate + sample count). Self-test runs before signing so a self-test failure fast-fails cheaply and a signed bundle always implies "passed self-test".
- Optional, secret-gated code-signing/notarization (see the table below). With no secrets the bundles ship **unsigned** and the deploy still completes.
- Each bundle is sha256'd. The `publish` job gathers both jars and every engine target, generates `engine-manifest.json` via `engine-kokoro/scripts/generate_manifest.py`, generates a changelog from the GitHub compare notes, creates the `v<version>` tag, and publishes a single GitHub Release carrying the thin jar, the shadow jar, the `engine-manifest.json` asset, and the bundles + their `.sha256` files. The manifest is a release asset the plugin fetches at runtime, not a committed resource, so there is no manifest commit-back.
- The publish step is re-runnable: re-running for an existing tag refreshes that release's assets.

## The manifest is the glue

The plugin jar ships tiny: no engine binary, no voice model, no engine URLs. `engine-manifest.json` is a small JSON asset published into each GitHub Release that binds that version's jar to that version's engine bundles. Its shape is flat and stable: a `version`, an `engine` name, and an `artifacts` map keyed by platform id (`osx-aarch64`, `linux-x64`, `win-x64`), each entry carrying `url`, `sha256`, `size`, `signed`, and `launcher`.

The jar only carries its own version, committed as the bundled resource `src/main/resources/plugin-version.txt` (the single source of truth: `build.gradle` reads the same file for the project version, and `build=standard` ships it verbatim, so the Hub-built jar carries it too without any Gradle-generated resource). At runtime `EngineInstaller` (`src/main/java/com/grahambartley/synthesis/engine/EngineInstaller.java`) fetches `engine-manifest.json` from the Release whose tag matches that version (`.../releases/download/v<version>/engine-manifest.json`) with the injected `OkHttpClient`, resolves the entry for the current OS/arch, downloads the bundle from its `url`, verifies the `sha256`, and extracts it under `~/.runelite/voiced-dialogue/engines/<engine>-<version>/`. On macOS it then clears the `com.apple.quarantine` extended attribute on the extracted files so Gatekeeper does not block an unsigned engine. The plugin runs the extracted launcher as the external `--stdio` process and reuses it on later runs (the install is idempotent: an already-extracted launcher is reused without re-downloading). Any fetch/download failure or hash mismatch fails cleanly to "backend unavailable" with no partial install — never a crash, never a game-thread block.

A dev/`SNAPSHOT` build has no matching published release, so the fetch is skipped and the local backend is simply unavailable — not an error, just nothing to install. Cutting the release for a version is what publishes the matching manifest + bundles, which is what makes that version's jar resolve a real engine.

## Release runbook (correct order)

Releases are **never automatic**. Cut one in this order:

1. **Bump the version in `src/main/resources/plugin-version.txt` and `runelite-plugin.properties`** to the release version on the commit you intend to tag, and merge it. The two must match (the `checkVersionConsistency` Gradle task fails the build otherwise); `plugin-version.txt` is the single source `build.gradle`, the deploy tag, and the engine release all share.
2. **Dispatch `Release`** from the Actions tab ("Release" -> "Run workflow"), choosing the `release_type` (`alpha`/`beta`/`stable`). One run reads that version, builds and validates both jars and the three Kokoro bundles, signs the bundles if secrets are present, tags `v<version>`, and publishes a single GitHub Release carrying the thin jar, the shadow jar, the `engine-manifest.json` asset, and the bundles. No commit-back: the manifest is a release asset.
3. **Submit/update the plugin in `runelite/plugin-hub`** at the tagged commit (see issue #31) so the Hub builds the thin jar from that source and serves it. The Hub-built jar carries the same committed `plugin-version.txt` version, so it resolves the engine published under the same tag. The shadow jar on the Release is the standalone/sideload artifact.
4. **Users install.** On first use of the local voice, the jar fetches the `engine-manifest.json` for its own version from that Release, downloads the per-OS engine bundle, verifies its sha256, and runs it.

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

The plugin and engine ship under **one version**, source-pinned in the committed resource `src/main/resources/plugin-version.txt` (with `runelite-plugin.properties` kept in lockstep by the `checkVersionConsistency` build guard). A single `v<version>` tag carries both jars, the engine bundles, and the `engine-manifest.json` asset, and every jar resolves the engine release matching its own version (the committed resource, shipped verbatim, including by the Hub build). To cut a new version, bump `plugin-version.txt` and `runelite-plugin.properties` on the commit you tag, then dispatch the `Release` deploy; it rebuilds and republishes both jars, the engine bundles, and the manifest together under that tag. Submit that same commit to the Hub.
