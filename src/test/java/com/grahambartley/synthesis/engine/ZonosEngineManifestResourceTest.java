package com.grahambartley.synthesis.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Guards the {@code zonos-engine-manifest.json} resource: a separate engine artifact from Kokoro,
 * resolved through the same {@link EngineInstaller} by passing {@link
 * EngineInstaller#ZONOS_MANIFEST_RESOURCE}. It mirrors the #36 manifest shape with {@code engine:
 * "zonos"}, and the committed copy is the dev placeholder (empty urls/sha) because no Zonos GPU
 * engine is published yet, so install degrades to "unavailable" rather than crashing.
 */
public class ZonosEngineManifestResourceTest {

  private static final String RESOURCE = "/zonos-engine-manifest.json";
  private static final String[] PLATFORMS = {"osx-aarch64", "osx-x64", "linux-x64", "win-x64"};

  @Test
  public void manifestResourceIsPresentAndWellShaped() throws Exception {
    JsonObject root = load();
    assertEquals("zonos", root.get("engine").getAsString());
    assertEquals(1, root.get("schemaVersion").getAsInt());
    assertNotNull("version field is required", root.get("version"));

    // The committed copy may be the dev placeholder (0.0.0-dev) or a real release tag
    // (e.g. zonos-v0.1.0) once the release workflow regenerates it; either is valid.
    String version = root.get("version").getAsString();
    assertTrue("version must not be blank, was: " + version, !version.trim().isEmpty());

    JsonObject artifacts = root.getAsJsonObject("artifacts");
    assertNotNull("artifacts object is required", artifacts);

    for (String platform : PLATFORMS) {
      assertTrue("missing artifact entry for " + platform, artifacts.has(platform));
      JsonObject entry = artifacts.getAsJsonObject(platform);
      assertTrue(platform + " entry needs a url field", entry.has("url"));
      assertTrue(platform + " entry needs a sha256 field", entry.has("sha256"));
      assertTrue(platform + " entry needs a launcher field", entry.has("launcher"));

      // Dev placeholder: empty url/sha so the installer treats it as "nothing to install".
      assertEquals(
          platform + " url should be empty in the dev manifest",
          "",
          entry.get("url").getAsString());
      assertEquals(
          platform + " sha256 should be empty in the dev manifest",
          "",
          entry.get("sha256").getAsString());

      String launcher = entry.get("launcher").getAsString();
      String expected = platform.startsWith("win") ? "zonos-engine.bat" : "zonos-engine";
      assertEquals("launcher mismatch for " + platform, expected, launcher);
    }
  }

  private JsonObject load() throws Exception {
    try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
      assertNotNull("zonos-engine-manifest.json must be bundled as a plugin resource", in);
      return new JsonParser()
          .parse(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }
}
