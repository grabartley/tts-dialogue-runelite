---
name: run-tests
description: Run and write tests for the TTS Dialogue RuneLite plugin (unit tests, manual testing). Use when asked to test, run tests, write tests, or verify changes.
---

# Run Tests

Run unit tests and manual tests for the TTS Dialogue RuneLite plugin.

## Quick Commands

```bash
# Apply code formatting
./gradlew spotlessApply

# Run unit tests
./gradlew test

# Full build with all checks (runs spotlessCheck)
./gradlew clean build

# Manual testing in the RuneLite dev client
# (run via the TTSDialoguePluginTest entry point; --developer-mode is required for login, --debug is optional)
./gradlew shadowJar
java -ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED -jar build/libs/tTSDialogue-1.0-SNAPSHOT-all.jar --developer-mode --debug
```

In-process Kokoro TTS is on by default, so manual audio testing needs no voice server running. The model downloads once (~349 MB) into `~/.runelite/tts-dialogue/` on first launch.

## Test Types

| Test Type | Location | Run Command |
|-----------|----------|-------------|
| Unit Tests (JUnit 4) | `src/test/java/` | `./gradlew test` |
| Manual Tests | RuneLite dev client | `./gradlew shadowJar` then run the shadow jar |

## Java 17 Required

The plugin targets Java 17 (`options.release.set(17)` in `build.gradle`). Ensure a Java 17 toolchain is active:
- **jenv**: `jenv local 17`
- **SDKMAN**: `sdk use java 17-amzn`

## Writing Tests

All code changes require unit tests. Test classes mirror the production class name under test with a `Test` suffix and live in the same package structure under `src/test/java`.

**Unit Test Example (JUnit 4):**
```java
package com.grahambartley;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VoiceManagerTest {
  @Test
  public void unknownNpcFallsBackToHumanMale() {
    // arrange the manager, act, then assert the selected voice profile
    assertEquals("human-male", selected);
  }
}
```

## Test Workflow

Before pushing changes:
1. `./gradlew spotlessApply` - Format code
2. `./gradlew test` - Run unit tests
3. `./gradlew clean build` - Full build with `spotlessCheck`
4. Run the shadow jar - Manual test if dialogue or voice behavior changed
