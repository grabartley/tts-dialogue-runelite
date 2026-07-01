---
name: run-tests
description: Run and write tests for the Voiced Dialogue RuneLite plugin (unit tests, manual testing). Use when asked to test, run tests, write tests, or verify changes.
---

# Run Tests

Run unit tests and manual tests for the Voiced Dialogue RuneLite plugin.

## Quick Commands

```bash
# Apply code formatting
./gradlew spotlessApply

# Run unit tests
./gradlew test

# Full build with all checks (runs spotlessCheck)
./gradlew clean build

# Manual testing in the RuneLite dev client
# (run via the VoicedDialoguePluginRunner entry point, the shadow jar's Main-Class; --developer-mode is required for login, --debug is optional)
./gradlew shadowJar
java -ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED -jar build/libs/voicedDialogue-*-all.jar --developer-mode --debug
```

The plugin voices dialogue through OpenRouter (cloud), so manual audio testing needs an OpenRouter API key set in the plugin config. There is no engine download or bundled model: the client stays silent until a key is supplied.

## Test Types

| Test Type | Location | Run Command |
|-----------|----------|-------------|
| Unit Tests (JUnit 4) | `src/test/java/` | `./gradlew test` |
| Manual Tests | RuneLite dev client | `./gradlew shadowJar` then run the shadow jar |

## Java toolchain and language level

Build with a Java 17 toolchain (tests compile at release 17). Ensure it is active:
- **jenv**: `jenv local 17`
- **SDKMAN**: `sdk use java 17-amzn`

The plugin's **main sources compile at release 11** (`compileJava` sets `options.release=11`), because the Plugin Hub's `build=standard` compiles them at Java 11 with its own injected `build.gradle`. Do not use Java 12+ syntax or APIs in `src/main` (no records, no pattern-matching `instanceof`, no `Stream.toList()`, etc.); `./gradlew compileJava` fails locally if you do, mirroring the Hub. Tests may use Java 17 freely.

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
