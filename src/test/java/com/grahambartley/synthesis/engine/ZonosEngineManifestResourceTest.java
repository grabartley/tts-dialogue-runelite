package com.grahambartley.synthesis.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
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
 * "zonos"}. The committed copy carries the published {@code zonos-v0.1.0} win-x64 split bundle,
 * while the macOS/Linux slots stay empty placeholders (Zonos is NVIDIA/win-x64 only for v1) so
 * install degrades to "unavailable" rather than crashing there.
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
      // A platform entry is either a single-file artifact (url) or a split artifact (parts).
      // Dev placeholders carry an empty url; the release workflow fills real values (and may
      // switch a populated platform to the split `parts` form), so do not require emptiness here.
      assertTrue(
          platform + " entry needs a url or parts field", entry.has("url") || entry.has("parts"));
      assertTrue(platform + " entry needs a sha256 field", entry.has("sha256"));
      assertTrue(platform + " entry needs a launcher field", entry.has("launcher"));

      String launcher = entry.get("launcher").getAsString();
      String expected = platform.startsWith("win") ? "zonos-engine.bat" : "zonos-engine";
      assertEquals("launcher mismatch for " + platform, expected, launcher);
    }
  }

  /**
   * Regression guard for #73: the published Zonos release lives under tag {@code zonos-v0.1.0}, not
   * the bare {@code v0.1.0} (which is the Kokoro release tag). If a populated win-x64 split entry
   * is committed, every part URL must point at the {@code releases/download/zonos-<version>/}
   * segment, otherwise the engine download 404s. Skipped when the committed copy is still the dev
   * placeholder.
   */
  @Test
  public void winX64PartUrlsUseTaggedReleaseSegment() throws Exception {
    JsonObject artifacts = load().getAsJsonObject("artifacts");
    JsonObject winX64 = artifacts.getAsJsonObject("win-x64");
    if (!winX64.has("parts")) {
      return; // dev placeholder: nothing populated to guard yet
    }

    String version =
        winX64.get("archive").getAsString().split("-win-x64")[0].replace("zonos-engine-", "");
    JsonArray parts = winX64.getAsJsonArray("parts");
    assertTrue("win-x64 split entry must list at least one part", parts.size() > 0);
    for (int i = 0; i < parts.size(); i++) {
      String url = parts.get(i).getAsJsonObject().get("url").getAsString();
      assertTrue(
          "part " + i + " url must use the zonos-" + version + " release tag segment, was: " + url,
          url.contains("/releases/download/zonos-" + version + "/"));
      assertTrue(
          "part " + i + " url must not use the bare-version release tag, was: " + url,
          !url.contains("/releases/download/" + version + "/"));
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
