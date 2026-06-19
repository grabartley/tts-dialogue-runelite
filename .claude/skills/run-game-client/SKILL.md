---
name: run-game-client
description: Runs the RuneLite dev client locally with the plugin for manual testing. Use when asked to launch, run, or start the game client for testing.
---

# Run Game Client

Runs the RuneLite dev client with the TTS Dialogue plugin loaded for manual testing.

## Quick Start

Ensure Java 17 is active (`jenv local 17` or `sdk use java 17-amzn`), build the plugin, then launch via the `com.grahambartley.TTSDialoguePluginTest` entry point (the shadow jar's `Main-Class`), passing `--developer-mode` so the dev client logs in and exposes developer tooling:

```bash
./gradlew shadowJar
java -ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED -jar build/libs/tTSDialogue-1.0-SNAPSHOT-all.jar --developer-mode --debug
```

`TTSDialoguePluginTest.main` forwards these program arguments to `RuneLite.main`. `--developer-mode` belongs only with this launcher and is required for login to work; omit it and login fails. `--debug` is optional and turns on RuneLite debug-level logging, which pairs well with the plugin's Debug Mode config toggle.

The entry point calls `ExternalPluginManager.loadBuiltin(TTSDialoguePlugin.class)` and starts RuneLite. You can also run that `main` directly from the IDE with the same VM options and the `--developer-mode` (and optional `--debug`) program arguments.

## Logging In (Jagex accounts)

Accounts migrated to a Jagex account cannot log in to a source-built client directly: the login attempt returns `401 Unauthorized` and drops back to the login screen. The source client instead reads launcher credentials from `~/.runelite/credentials.properties` (the `JX_*` session tokens). When those tokens expire you get the same `401`, and they must be refreshed.

To write or refresh the credentials (requires RuneLite launcher 2.6.3+):

1. macOS: `/Applications/RuneLite.app/Contents/MacOS/RuneLite --configure`, then add `--insecure-write-credentials` to the `Client arguments` box and save.
2. Launch RuneLite once through the Jagex launcher so it writes fresh `JX_*` tokens into `~/.runelite/credentials.properties`.
3. Re-run the dev client (`TTSDialoguePluginTest`); it picks up the saved credentials and logs in without a password.

Keep `credentials.properties` private, and delete it (or use "End sessions" on the account site) to return the client to normal.

## TTS Engine

The plugin synthesizes dialogue in-process with the embedded Kokoro model via `sherpa-onnx`, on by default. No external voice server, Docker container, or `localhost` port is needed for audio.

On first launch the plugin downloads the Kokoro model bundle (~349 MB) once into `~/.runelite/tts-dialogue/` and caches it; the model loads on a background thread, so dialogue may stay silent only until the load finishes (watch the logs for `Kokoro model loaded`). Every later line is generated locally with no network call.

The legacy HTTP voice servers remain available behind the **In-Process TTS (Kokoro)** config toggle. Only when that toggle is off does the plugin POST to a `localhost` server, which must then be running for audio.

## Testing Flow

- Log into the dev client with a test account.
- Talk to an NPC to trigger a dialogue widget and confirm the NPC voice plays.
- Advance through dialogue to confirm playback interrupts cleanly on skipped lines.
- Trigger player dialogue and confirm the configured player voice plays.
- Toggle plugin config options (volume, race-based voices, player voice) and confirm behavior changes.

## Post-Test Protocol

After running the game client for manual testing, always ask for human input before continuing with next steps.
