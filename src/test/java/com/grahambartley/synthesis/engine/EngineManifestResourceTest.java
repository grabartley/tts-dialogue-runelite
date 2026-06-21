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
 * Guards the {@code engine-manifest.json} resource shape that the external Kokoro engine pipeline
 * (issue #36) owns and the plugin's {@code EngineInstaller} (issue #32) consumes via {@code
 * getResourceAsStream}.
 *
 * <p>The release workflow regenerates this resource so its URLs and checksums match the published
 * artifacts; the dev copy in the repo carries empty values. This test pins the contract every
 * version must satisfy: a top-level engine/version, and a per-platform entry for each of the three
 * supported targets carrying a download URL, sha256, and launcher name. Keeping the shape under
 * test lets #32 code against it without re-deriving the schema.
 */
public class EngineManifestResourceTest {

  private static final String RESOURCE = "/engine-manifest.json";
  private static final String[] PLATFORMS = {"osx-aarch64", "linux-x64", "win-x64"};

  @Test
  public void manifestResourceIsPresentAndWellShaped() throws Exception {
    JsonObject root = load();
    assertEquals("kokoro", root.get("engine").getAsString());
    assertEquals(1, root.get("schemaVersion").getAsInt());
    assertNotNull("version field is required", root.get("version"));
    // The repo-committed copy may be the dev placeholder (e.g. 0.0.0-dev) or a real release tag
    // (e.g. v0.1.0) once the release workflow has regenerated it; either is valid. Pin that the
    // version is present and non-blank rather than forcing the un-released shape.
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

      String launcher = entry.get("launcher").getAsString();
      String expected = platform.startsWith("win") ? "kokoro-engine.bat" : "kokoro-engine";
      assertEquals("launcher mismatch for " + platform, expected, launcher);
    }
  }

  private JsonObject load() throws Exception {
    try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
      assertNotNull("engine-manifest.json must be bundled as a plugin resource", in);
      // The bundled Gson predates the static JsonParser.parseReader API, so use the instance
      // method.
      return new JsonParser()
          .parse(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }
}
