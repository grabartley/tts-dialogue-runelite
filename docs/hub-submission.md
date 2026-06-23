# Plugin Hub submission runbook

How to list **Voiced Dialogue** (internal name `voiced-dialogue`) on the official
[RuneLite Plugin Hub](https://github.com/runelite/plugin-hub). This is a maintainer
action performed once the engine release bundles exist; it is not part of the normal
plugin build.

## How the Hub consumes this repo

The Hub does not host the plugin jar. It hosts a one-file *commit descriptor* per plugin
(`plugins/voiced-dialogue`) containing only a `repository=` and a `commit=`. The Hub
packager clones this repo at that exact commit, builds the jar from `src/main`, reads
`runelite-plugin.properties` for the display metadata (`displayName`, `author`,
`description`, `tags`), and reads the descriptor for the listing `warning=`/`authors=`.

The split matters:

| Field | Lives in | Why |
|-------|----------|-----|
| `displayName`, `author`, `description`, `tags` | `runelite-plugin.properties` (this repo) | Read from the repo at the tagged commit. |
| `repository`, `commit` | `plugins/voiced-dialogue` descriptor (plugin-hub fork) | The only required descriptor fields. |
| `warning`, `authors`, `jarSizeLimitMiB` | `plugins/voiced-dialogue` descriptor (plugin-hub fork) | The packager reads these from the descriptor, not from the properties file. A `warning=` in `runelite-plugin.properties` is an unused prop and never reaches the user; it must go in the descriptor. |

The off-machine-data disclosure (Azure) is therefore carried by the descriptor's
`warning=` line, mirroring how the `tts` and NaturalSpeech listings disclose off-machine
data. A pre-filled descriptor is kept at
[`docs/plugin-hub-manifest/voiced-dialogue`](plugin-hub-manifest/voiced-dialogue) in this
repo; copy it into the fork and fill in the commit.

## Prerequisites (must be true before submitting)

- The repository is public.
- A `LICENSE` exists at the repo root (this repo ships MIT).
- The Kokoro engine release bundle is published to GitHub Releases and the committed
  `engine-manifest.json` points at real, sha256-verified download URLs. Until that release is
  cut, the local backend cannot install at runtime, so do not submit before the engine pipeline
  (epic #34) ships it.
- `runelite-plugin.properties` is non-placeholder (no `Example` / `Nobody` /
  `An example greeter plugin`, which the packager rejects).
- The plugin jar builds clean: no native libraries, no model, well under the 10 MiB
  source/jar limit. `./gradlew jar` produces a ~90 KiB jar.

## Step 1: Tag a release commit on this repo

Pick the commit you want the Hub to build and tag it so the descriptor points at an
immutable sha.

```bash
git checkout main && git pull
git tag -a v1.0.0 -m "Voiced Dialogue v1.0.0"
git push origin v1.0.0
git rev-parse v1.0.0   # copy the full 40-char sha for the descriptor
```

## Step 2: Fork and branch plugin-hub

```bash
# fork https://github.com/runelite/plugin-hub once, via the GitHub UI or:
gh repo fork runelite/plugin-hub --clone
cd plugin-hub
git checkout -B voiced-dialogue upstream/master
```

## Step 3: Add the descriptor

Create `plugins/voiced-dialogue` (no file extension) by copying
`docs/plugin-hub-manifest/voiced-dialogue` from this repo and replacing the commit
placeholder with the sha from Step 1:

```
repository=https://github.com/grabartley/tts-dialogue-runelite.git
commit=<full 40-char sha from `git rev-parse v1.0.0`>
authors=grabartley
warning=With the Cloud (Azure) voice backend selected, the dialogue text being spoken and your configured Azure region are sent to Microsoft Azure over HTTPS using your subscription key. The two local backends stay fully offline and send nothing off your machine.
```

The descriptor file name **is** the internal plugin name and must be lowercase
alphanumeric plus dashes: `voiced-dialogue`.

## Step 4: Open the PR against runelite/plugin-hub

```bash
git add plugins/voiced-dialogue
git commit -m "add voiced-dialogue"
git push -u origin voiced-dialogue
gh pr create -R runelite/plugin-hub -w
```

Write a short PR description of what the plugin does.

## Step 5: Watch CI and iterate

The PR runs `.github/workflows/build.yml / build (pull_request)` and a `RuneLite Plugin
Hub Checks` job:

- A green check on both means it built and passed the automated audit.
- If `Hub Checks` says **Changes are needed**, read the requested changes, fix them on
  this repo, push a new tag/commit, update `commit=` in the descriptor, and push again to
  the same PR (keep everything in one PR; do not open new ones).

Common automated-audit failures and how this repo already avoids them:

| Check | Status here |
|-------|-------------|
| No bundled native libraries / no model in the jar | The engine + model are downloaded at runtime by `EngineInstaller`; the jar is classes + four JSON resources only. |
| No `new OkHttpClient()` / `new OkHttpClient.Builder()` / `new Gson()` / `new GsonBuilder()` (disallowed APIs) | All HTTP/JSON uses the injected `OkHttpClient` / `Gson`. |
| Resources via `getResourceAsStream` (jar not unpacked) | All bundled JSON loads via `getResourceAsStream`. |
| Jar under 10 MiB | ~90 KiB. No `jarSizeLimitMiB` override needed. |
| `displayName` / `author` / `description` not the template placeholders | Set to real values. |

## Step 6: Updating later

To ship a new version after merge, update only `commit=` in `plugins/voiced-dialogue` on a
fresh branch off `upstream/master` and open another small PR.

## Out of scope for this runbook

Cutting and signing the Kokoro engine release bundle (epic #34) is a separate
operational step that must complete first. This runbook covers only the Hub listing.
