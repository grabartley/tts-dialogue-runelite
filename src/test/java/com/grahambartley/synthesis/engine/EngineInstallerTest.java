package com.grahambartley.synthesis.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link EngineInstaller}: pure OS/arch -> platform-id resolution, sha256
 * verification, zip extraction, and the full download path served by a local HTTP server with a
 * fake artifact (no real GitHub release exists yet, so this proves the manifest-driven flow
 * end-to-end with a stand-in URL). The bundled dev manifest (empty url/sha256) is also exercised to
 * confirm it degrades to "no engine" rather than crashing.
 */
public class EngineInstallerTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final Gson gson = new Gson();
  private HttpServer server;

  @After
  public void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  // --- pure platform-id mapping ------------------------------------------------------------------

  @Test
  public void platformIdResolvesEachSupportedTarget() {
    assertEquals("osx-aarch64", EngineInstaller.platformId("Mac OS X", "aarch64"));
    assertEquals("osx-x64", EngineInstaller.platformId("Mac OS X", "x86_64"));
    assertEquals("linux-x64", EngineInstaller.platformId("Linux", "amd64"));
    assertEquals("win-x64", EngineInstaller.platformId("Windows 11", "amd64"));
  }

  @Test
  public void platformIdIsNullForUnsupportedArch() {
    assertNull(EngineInstaller.platformId("Linux", "arm"));
    assertNull(EngineInstaller.platformId("SomeOtherOS", "amd64"));
  }

  // --- sha256 + zip helpers ----------------------------------------------------------------------

  @Test
  public void sha256HexMatchesKnownDigest() throws IOException {
    Path f = tmp.newFile("hello.txt").toPath();
    Files.write(f, "abc".getBytes(StandardCharsets.UTF_8));
    // Known SHA-256 of "abc".
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        EngineInstaller.sha256Hex(f));
  }

  @Test
  public void extractZipWritesEntriesAndRejectsTraversal() throws IOException {
    Path zip = tmp.newFile("bundle.zip").toPath();
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
      zos.putNextEntry(new ZipEntry("kokoro-engine"));
      zos.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
      zos.putNextEntry(new ZipEntry("lib/x.jar"));
      zos.write(new byte[] {1, 2, 3});
      zos.closeEntry();
    }
    Path dest = tmp.newFolder("out").toPath();
    EngineInstaller.extractZip(zip, dest);
    assertTrue(Files.isRegularFile(dest.resolve("kokoro-engine")));
    assertTrue(Files.isRegularFile(dest.resolve("lib/x.jar")));
  }

  // --- full install via local HTTP server --------------------------------------------------------

  @Test
  public void installDownloadsVerifiesExtractsAndIsIdempotent() throws Exception {
    // Build a fake engine bundle zip whose launcher matches the manifest entry.
    byte[] zipBytes = buildBundleZip();
    String sha = sha256HexOf(zipBytes);
    String url = serve("/kokoro.zip", zipBytes);

    Path enginesRoot = tmp.newFolder("engines").toPath();
    String platform = EngineInstaller.currentPlatformId();
    String launcherName = platform.startsWith("win") ? "kokoro-engine.bat" : "kokoro-engine";
    EngineInstaller installer =
        installerWithManifest(enginesRoot, manifest(platform, url, sha, launcherName));

    EngineInstaller.Installed first = installer.install();
    assertNotNull("install should resolve a launcher", first);
    assertEquals(launcherName, first.launcher().getFileName().toString());
    assertTrue(Files.isRegularFile(first.launcher()));
    assertEquals("kokoro", first.engine());

    // Idempotent: a second install reuses the extracted bundle (the server is stopped so a second
    // download would fail) and returns the same launcher.
    server.stop(0);
    server = null;
    EngineInstaller.Installed second = installer.install();
    assertNotNull("second install should reuse the extracted bundle", second);
    assertEquals(first.launcher(), second.launcher());
  }

  // --- split (multi-part) install
  // -----------------------------------------------------------------

  @Test
  public void splitInstallDownloadsReassemblesVerifiesAndExtracts() throws Exception {
    // A real bundle zip, split into ordered byte ranges, each served at its own URL. The installer
    // must download every part, verify each part's sha256, concatenate IN ORDER, verify the
    // combined
    // sha256, then extract via the existing path.
    byte[] zipBytes = buildBundleZip("zonos-engine", "zonos-engine.bat");
    String combinedSha = sha256HexOf(zipBytes);
    byte[][] chunks = splitInto(zipBytes, 3);

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    StringBuilder partsJson = new StringBuilder("[");
    for (int i = 0; i < chunks.length; i++) {
      String path = "/part" + i;
      registerStaticBody(path, chunks[i]);
      if (i > 0) {
        partsJson.append(',');
      }
      partsJson
          .append("{\"url\":\"")
          .append(baseUrl())
          .append(path)
          .append("\",\"sha256\":\"")
          .append(sha256HexOf(chunks[i]))
          .append("\",\"size\":")
          .append(chunks[i].length)
          .append('}');
    }
    partsJson.append(']');
    server.start();

    Path enginesRoot = tmp.newFolder("engines").toPath();
    String platform = EngineInstaller.currentPlatformId();
    String launcherName = platform.startsWith("win") ? "zonos-engine.bat" : "zonos-engine";
    EngineInstaller installer =
        installerWithManifest(
            enginesRoot, splitManifest(platform, partsJson.toString(), combinedSha, launcherName));

    EngineInstaller.Installed installed = installer.install();
    assertNotNull("split install should resolve a launcher", installed);
    assertEquals(launcherName, installed.launcher().getFileName().toString());
    assertTrue(Files.isRegularFile(installed.launcher()));
    assertEquals("zonos", installed.engine());
    // No part temp files left behind in the engines root.
    try (java.util.stream.Stream<Path> stale =
        Files.list(enginesRoot).filter(p -> p.getFileName().toString().contains(".part"))) {
      assertEquals("part temp files must be cleaned up", 0, stale.count());
    }
  }

  @Test
  public void splitInstallWithCorruptPartFailsGracefully() throws Exception {
    byte[] zipBytes = buildBundleZip();
    String combinedSha = sha256HexOf(zipBytes);
    byte[][] chunks = splitInto(zipBytes, 3);

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    StringBuilder partsJson = new StringBuilder("[");
    for (int i = 0; i < chunks.length; i++) {
      String path = "/part" + i;
      registerStaticBody(path, chunks[i]);
      if (i > 0) {
        partsJson.append(',');
      }
      // Advertise a WRONG sha256 for the middle part: its per-part verify must fail.
      String advertised = i == 1 ? "deadbeef".repeat(8) : sha256HexOf(chunks[i]);
      partsJson
          .append("{\"url\":\"")
          .append(baseUrl())
          .append(path)
          .append("\",\"sha256\":\"")
          .append(advertised)
          .append("\",\"size\":")
          .append(chunks[i].length)
          .append('}');
    }
    partsJson.append(']');
    server.start();

    Path enginesRoot = tmp.newFolder("engines").toPath();
    String platform = EngineInstaller.currentPlatformId();
    String launcherName = platform.startsWith("win") ? "zonos-engine.bat" : "zonos-engine";
    EngineInstaller installer =
        installerWithManifest(
            enginesRoot, splitManifest(platform, partsJson.toString(), combinedSha, launcherName));

    assertNull("a corrupt part must yield null, not a crash", installer.install());
    // No half-extracted launcher and no stray part files.
    Path installDir = enginesRoot.resolve("zonos-0.0.0-dev");
    assertTrue(
        "no launcher should be installed on failure",
        !Files.exists(installDir.resolve(launcherName)));
    try (java.util.stream.Stream<Path> stale =
        Files.list(enginesRoot).filter(p -> p.getFileName().toString().contains(".part"))) {
      assertEquals("no part temp files should remain on failure", 0, stale.count());
    }
  }

  @Test
  public void shaMismatchFailsGracefully() throws Exception {
    byte[] zipBytes = buildBundleZip();
    String url = serve("/kokoro.zip", zipBytes);
    Path enginesRoot = tmp.newFolder("engines").toPath();
    String platform = EngineInstaller.currentPlatformId();
    String launcherName = platform.startsWith("win") ? "kokoro-engine.bat" : "kokoro-engine";

    EngineInstaller installer =
        installerWithManifest(
            enginesRoot, manifest(platform, url, "deadbeef".repeat(8), launcherName));

    assertNull("a sha256 mismatch must yield null, not a crash", installer.install());
  }

  @Test
  public void emptyDevManifestEntryYieldsNullNotCrash() throws Exception {
    Path enginesRoot = tmp.newFolder("engines").toPath();
    String platform = EngineInstaller.currentPlatformId();
    String launcherName = platform.startsWith("win") ? "kokoro-engine.bat" : "kokoro-engine";
    // Empty url/sha256 mirrors the committed dev manifest.
    EngineInstaller installer =
        installerWithManifest(enginesRoot, manifest(platform, "", "", launcherName));
    assertNull(installer.install());
  }

  @Test
  public void bundledDevManifestResourceYieldsNull() {
    // The real committed /engine-manifest.json resource has empty urls -> no installable engine.
    Path enginesRoot;
    try {
      enginesRoot = tmp.newFolder("engines-bundled").toPath();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    EngineInstaller installer = new EngineInstaller(new OkHttpClient(), gson, enginesRoot);
    assertNull("dev manifest resource must degrade to no-engine", installer.install());
  }

  @Test
  public void bundledZonosDevManifestResourceYieldsNull() {
    // The committed /zonos-engine-manifest.json resource has empty urls -> no installable engine,
    // resolved through the same installer via the Zonos manifest-resource constructor.
    Path enginesRoot;
    try {
      enginesRoot = tmp.newFolder("engines-zonos").toPath();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    EngineInstaller installer =
        new EngineInstaller(
            new OkHttpClient(), gson, enginesRoot, EngineInstaller.ZONOS_MANIFEST_RESOURCE);
    assertNull("dev Zonos manifest resource must degrade to no-engine", installer.install());
  }

  // --- helpers -----------------------------------------------------------------------------------

  /**
   * Returns an installer whose {@code readManifest} is overridden to a supplied JSON string, so the
   * test controls the manifest without touching the committed dev resource.
   */
  private EngineInstaller installerWithManifest(Path enginesRoot, String manifestJson) {
    com.google.gson.JsonObject parsed =
        gson.fromJson(manifestJson, com.google.gson.JsonObject.class);
    return new EngineInstaller(new OkHttpClient(), gson, enginesRoot) {
      @Override
      protected com.google.gson.JsonObject readManifest() {
        return parsed;
      }
    };
  }

  private String manifest(String platform, String url, String sha, String launcher) {
    return "{\"schemaVersion\":1,\"engine\":\"kokoro\",\"version\":\"0.0.0-dev\",\"artifacts\":{\""
        + platform
        + "\":{\"url\":\""
        + url
        + "\",\"sha256\":\""
        + sha
        + "\",\"launcher\":\""
        + launcher
        + "\"}}}";
  }

  /** A split (Zonos) manifest: a {@code parts} array + combined sha256 for the reassembled zip. */
  private String splitManifest(
      String platform, String partsJson, String combinedSha, String launcher) {
    return "{\"schemaVersion\":1,\"engine\":\"zonos\",\"version\":\"0.0.0-dev\",\"artifacts\":{\""
        + platform
        + "\":{\"archive\":\"zonos.zip\",\"sha256\":\""
        + combinedSha
        + "\",\"launcher\":\""
        + launcher
        + "\",\"parts\":"
        + partsJson
        + "}}}";
  }

  /** Splits {@code data} into {@code n} contiguous chunks (last absorbs the remainder). */
  private static byte[][] splitInto(byte[] data, int n) {
    byte[][] chunks = new byte[n][];
    int base = data.length / n;
    int offset = 0;
    for (int i = 0; i < n; i++) {
      int len = (i == n - 1) ? data.length - offset : base;
      chunks[i] = java.util.Arrays.copyOfRange(data, offset, offset + len);
      offset += len;
    }
    return chunks;
  }

  /** Registers a fixed-body GET handler on the current (not-yet-started) {@link #server}. */
  private void registerStaticBody(String path, byte[] body) {
    server.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private byte[] buildBundleZip() throws IOException {
    return buildBundleZip("kokoro-engine", "kokoro-engine.bat");
  }

  private byte[] buildBundleZip(String... launchers) throws IOException {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      for (String name : launchers) {
        zos.putNextEntry(new ZipEntry(name));
        zos.write("launcher".getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
      zos.putNextEntry(new ZipEntry("lib/engine.jar"));
      zos.write(new byte[] {9, 8, 7});
      zos.closeEntry();
    }
    return bos.toByteArray();
  }

  private String serve(String path, byte[] body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private static String sha256HexOf(byte[] data) throws Exception {
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
    byte[] d = md.digest(data);
    StringBuilder sb = new StringBuilder();
    for (byte b : d) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16));
      sb.append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }
}
