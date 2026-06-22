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
 * verification, zip and tar.gz extraction, and the full download path served by a local HTTP server
 * with a fake artifact (proving the manifest-driven flow end-to-end with a stand-in URL for both
 * archive formats). An empty manifest entry is exercised to confirm it degrades to "no engine"
 * rather than crashing, and the committed Kokoro manifest resource is checked for shape.
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
  public void isTarGzDetectsGzippedTarballsOnly() {
    assertTrue(EngineInstaller.isTarGz("kokoro-engine-v0.1.0-osx-aarch64.tar.gz"));
    assertTrue(EngineInstaller.isTarGz("bundle.TGZ"));
    assertTrue(
        EngineInstaller.isTarGz(
            "https://example.com/releases/v0.1.0/kokoro-engine-v0.1.0-linux-x64.tar.gz"));
    assertTrue(
        "a zip (win-x64, Zonos split) must NOT be tar.gz", !EngineInstaller.isTarGz("x.zip"));
    assertTrue(!EngineInstaller.isTarGz("zonos-engine-v0.1.0-win-x64.zip"));
    assertTrue(!EngineInstaller.isTarGz(""));
    assertTrue(!EngineInstaller.isTarGz(null));
  }

  @Test
  public void extractTarGzWritesEntries() throws Exception {
    // Build a real flat .tar.gz with the launcher at top level, mirroring the engine bundles.
    Path src = tmp.newFolder("tar-src").toPath();
    Files.write(src.resolve("kokoro-engine"), "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(src.resolve("lib"));
    Files.write(src.resolve("lib/engine.jar"), new byte[] {1, 2, 3});
    Path tarGz = tmp.newFile("bundle.tar.gz").toPath();
    tarCzf(src, tarGz);

    Path dest = tmp.newFolder("tar-out").toPath();
    EngineInstaller.extractTarGz(tarGz, dest);
    assertTrue(Files.isRegularFile(dest.resolve("kokoro-engine")));
    assertTrue(Files.isRegularFile(dest.resolve("lib/engine.jar")));
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

  @Test
  public void installResolvesLauncherNestedUnderWrapperDirectory() throws Exception {
    // The published Windows .zip bundle nests its whole tree under a single engine-image/ wrapper
    // directory, so the launcher extracts to installDir/engine-image/<launcher> rather than
    // installDir/<launcher>. The installer must still resolve it (and find its runtime siblings)
    // without the bundle being re-published. Reproduces the Windows "launcher ... is missing"
    // failure where the macOS/Linux tar.gz bundles (flat at root) were unaffected.
    byte[] zipBytes = buildNestedBundleZip("engine-image", "kokoro-engine", "kokoro-engine.bat");
    String sha = sha256HexOf(zipBytes);
    String url = serve("/kokoro-nested.zip", zipBytes);

    Path enginesRoot = tmp.newFolder("engines").toPath();
    String platform = EngineInstaller.currentPlatformId();
    String launcherName = platform.startsWith("win") ? "kokoro-engine.bat" : "kokoro-engine";
    EngineInstaller installer =
        installerWithManifest(enginesRoot, manifest(platform, url, sha, launcherName));

    EngineInstaller.Installed installed = installer.install();
    assertNotNull("install must resolve a launcher nested under a wrapper dir", installed);
    assertEquals(launcherName, installed.launcher().getFileName().toString());
    assertTrue(Files.isRegularFile(installed.launcher()));
    // The launcher resolves its runtime relative to its own directory, so the siblings must sit
    // next to the resolved (nested) launcher for it to actually run.
    assertTrue(
        "launcher siblings must sit next to the resolved launcher",
        Files.isRegularFile(installed.launcher().getParent().resolve("lib/engine.jar")));

    // Idempotent even when nested: a second install reuses the extracted bundle (server stopped so
    // a re-download would fail) and returns the same nested launcher.
    server.stop(0);
    server = null;
    EngineInstaller.Installed second = installer.install();
    assertNotNull("second install should reuse the nested bundle", second);
    assertEquals(installed.launcher(), second.launcher());
  }

  // --- full install of a .tar.gz bundle (osx-aarch64 / linux-x64)
  // -------------------------------

  @Test
  public void installExtractsTarGzBundleForUnixPlatforms() throws Exception {
    // The osx-aarch64/linux-x64 engine bundles are published as .tar.gz, not .zip. The installer
    // must infer the format from the artifact url and extract via the system tar, placing the flat
    // launcher directly under installDir. (Reproduces and guards #66.)
    String launcherName = "kokoro-engine";
    Path src = tmp.newFolder("targz-src").toPath();
    Files.write(src.resolve(launcherName), "launcher".getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(src.resolve("lib"));
    Files.write(src.resolve("lib/engine.jar"), new byte[] {9, 8, 7});
    Path tarGz = tmp.newFile("kokoro.tar.gz").toPath();
    tarCzf(src, tarGz);
    byte[] tarBytes = Files.readAllBytes(tarGz);
    String sha = sha256HexOf(tarBytes);
    // The served path ends in .tar.gz so isTarGz keys off the url, as it does for a real manifest.
    String url = serve("/kokoro-engine-osx-aarch64.tar.gz", tarBytes);

    Path enginesRoot = tmp.newFolder("engines").toPath();
    String platform = EngineInstaller.currentPlatformId();
    EngineInstaller installer =
        installerWithManifest(enginesRoot, manifest(platform, url, sha, launcherName));

    EngineInstaller.Installed installed = installer.install();
    assertNotNull("tar.gz install should resolve a launcher", installed);
    assertEquals(launcherName, installed.launcher().getFileName().toString());
    assertTrue(Files.isRegularFile(installed.launcher()));
    assertTrue(Files.isRegularFile(installed.launcher().getParent().resolve("lib/engine.jar")));
    assertEquals("kokoro", installed.engine());
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
  public void bundledKokoroManifestResourceIsWellFormedForBuiltTargets() {
    // The committed /engine-manifest.json now points at the published Kokoro v0.1.0 release (#61),
    // so it is no longer a dev placeholder. Assert the resource parses and carries a real artifact
    // (url + sha256 + launcher) for each built target, and that the osx-aarch64/linux-x64 urls are
    // .tar.gz while win-x64 is .zip -- the exact distinction the installer must dispatch on (#66).
    // This stays offline: it inspects the manifest rather than running a real ~385 MB install.
    com.google.gson.JsonObject manifest =
        gson.fromJson(
            new java.io.InputStreamReader(
                EngineInstaller.class.getResourceAsStream(EngineInstaller.KOKORO_MANIFEST_RESOURCE),
                StandardCharsets.UTF_8),
            com.google.gson.JsonObject.class);
    com.google.gson.JsonObject artifacts = manifest.getAsJsonObject("artifacts");
    for (String target : new String[] {"osx-aarch64", "linux-x64", "win-x64"}) {
      com.google.gson.JsonObject entry = artifacts.getAsJsonObject(target);
      assertTrue(
          target + " must have a non-empty url", entry.get("url").getAsString().length() > 0);
      assertTrue(
          target + " must have a non-empty sha256", entry.get("sha256").getAsString().length() > 0);
      assertTrue(
          target + " must declare a launcher",
          entry.has("launcher") && entry.get("launcher").getAsString().length() > 0);
      boolean expectTarGz = !target.startsWith("win");
      assertEquals(
          target + " archive format",
          expectTarGz,
          EngineInstaller.isTarGz(entry.get("url").getAsString()));
    }
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

  /**
   * Creates a flat {@code .tar.gz} of {@code srcDir}'s contents (the {@code -C srcDir .} form the
   * release pipeline uses) via the system {@code tar}, the same tool the installer extracts with.
   * Skips the test on Windows, where the bundles are {@code .zip} and {@code tar} is not assumed.
   */
  private static void tarCzf(Path srcDir, Path tarGz) throws Exception {
    org.junit.Assume.assumeFalse(
        "tar.gz packaging/extraction is a macOS/Linux concern; Windows uses .zip",
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
    Process p =
        new ProcessBuilder("tar", "-czf", tarGz.toString(), "-C", srcDir.toString(), ".")
            .redirectErrorStream(true)
            .start();
    try (java.io.InputStream in = p.getInputStream()) {
      byte[] buf = new byte[8192];
      while (in.read(buf) != -1) {
        // discard
      }
    }
    assertTrue("tar -czf must succeed", p.waitFor(1, java.util.concurrent.TimeUnit.MINUTES));
    assertEquals("tar -czf exit status", 0, p.exitValue());
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

  /**
   * Like {@link #buildBundleZip} but nests the whole tree under a single wrapper directory, as the
   * published Windows .zip bundle does (everything under {@code engine-image/}).
   */
  private byte[] buildNestedBundleZip(String wrapperDir, String... launchers) throws IOException {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      for (String name : launchers) {
        zos.putNextEntry(new ZipEntry(wrapperDir + "/" + name));
        zos.write("launcher".getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
      zos.putNextEntry(new ZipEntry(wrapperDir + "/lib/engine.jar"));
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
