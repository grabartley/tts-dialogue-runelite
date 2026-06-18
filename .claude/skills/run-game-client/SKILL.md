---
description: Runs the RuneLite dev client locally with the plugin for manual testing. Use when asked to launch, run, or start the game client for testing.
---

# Run Game Client

Runs the RuneLite dev client with the TTS Dialogue plugin loaded for manual testing.

## Quick Start

Ensure Java 17 is active (`jenv local 17` or `sdk use java 17-amzn`), build the plugin, then launch the RuneLite client with the plugin loaded as a builtin:

```bash
./gradlew shadowJar
java -ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED -jar build/libs/tTSDialogue-1.0-SNAPSHOT-all.jar
```

The entry point is `com.grahambartley.TTSDialoguePluginTest`, which calls `ExternalPluginManager.loadBuiltin(TTSDialoguePlugin.class)` and starts RuneLite. You can also run that `main` directly from the IDE with the same VM options.

## TTS Server Dependency

The plugin speaks dialogue by calling a local TTS voice server. For manual voice playback to work, the local TTS server must be running and reachable on `localhost`. If no server is up, dialogue capture still works but no audio is produced.

## Testing Flow

- Log into the dev client with a test account.
- Talk to an NPC to trigger a dialogue widget and confirm the NPC voice plays.
- Advance through dialogue to confirm playback interrupts cleanly on skipped lines.
- Trigger player dialogue and confirm the configured player voice plays.
- Toggle plugin config options (volume, race-based voices, player voice) and confirm behavior changes.

## Post-Test Protocol

After running the game client for manual testing, always ask for human input before continuing with next steps.
