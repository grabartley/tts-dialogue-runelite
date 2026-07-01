---
name: run-game-client
description: Runs the RuneLite dev client locally with the plugin for manual testing. Use when asked to launch, run, or start the game client for testing.
---

# Run Game Client

Runs the RuneLite dev client with the Voiced Dialogue plugin loaded for manual testing.

## Quick Start

Ensure Java 17 is active (`jenv local 17` or `sdk use java 17-amzn`), build the plugin, then launch via the `com.grahambartley.VoicedDialoguePluginRunner` entry point (the shadow jar's `Main-Class`), passing `--developer-mode` so the dev client logs in and exposes developer tooling:

```bash
./gradlew shadowJar
java -ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED -jar build/libs/voicedDialogue-*-all.jar --developer-mode --debug 2>&1 | tee /tmp/tts-client.log
```

`VoicedDialoguePluginRunner.main` forwards these program arguments to `RuneLite.main`. `--developer-mode` belongs only with this launcher and is required for login to work; omit it and login fails. `--debug` is optional and turns on RuneLite debug-level logging, which pairs well with the plugin's Debug Mode config toggle.

**Always write the client output to `/tmp/tts-client.log`** (the `tee` above, or `> /tmp/tts-client.log 2>&1 &` when launching in the background) so the logs can be read and grepped during and after testing. With Debug Mode on, that file carries the `[TTS profile]` / `[TTS voice]` / `[TTS cloud]` traces used to diagnose voice issues (see the `diagnose-npc-voice` skill).

The entry point calls `ExternalPluginManager.loadBuiltin(VoicedDialoguePlugin.class)` and starts RuneLite. You can also run that `main` directly from the IDE with the same VM options and the `--developer-mode` (and optional `--debug`) program arguments.

## Logging In (Jagex accounts)

Accounts migrated to a Jagex account cannot log in to a source-built client directly: the login attempt returns `401 Unauthorized` and drops back to the login screen. The source client instead reads launcher credentials from `~/.runelite/credentials.properties` (the `JX_*` session tokens). When those tokens expire you get the same `401`, and they must be refreshed.

To write or refresh the credentials (requires RuneLite launcher 2.6.3+):

1. macOS: `/Applications/RuneLite.app/Contents/MacOS/RuneLite --configure`, then add `--insecure-write-credentials` to the `Client arguments` box and save.
2. Launch RuneLite once through the Jagex launcher so it writes fresh `JX_*` tokens into `~/.runelite/credentials.properties`.
3. Re-run the dev client (`VoicedDialoguePluginRunner`); it picks up the saved credentials and logs in without a password.

Keep `credentials.properties` private, and delete it (or use "End sessions" on the account site) to return the client to normal.

## Hearing Audio (OpenRouter API key)

The plugin voices dialogue through OpenRouter (cloud) and does nothing until an OpenRouter API key is set. There is no engine download, no bundled model, no Docker container, and no `localhost` port: the client stays silent until you supply a key.

To hear anything, open the Voiced Dialogue plugin config in the running client and paste your OpenRouter API key into the API key field. Once the key is set, talk to an NPC and the line is synthesized in the cloud and played back locally.

## Testing Flow

- Log into the dev client with a test account.
- Talk to an NPC to trigger a dialogue widget and confirm the NPC voice plays.
- Advance through dialogue to confirm playback interrupts cleanly on skipped lines.
- Trigger player dialogue and confirm the configured player voice plays.
- Toggle plugin config options (volume, race-based voices, player voice) and confirm behavior changes.

## Post-Test Protocol

After running the game client for manual testing, always ask for human input before continuing with next steps.
