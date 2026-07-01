package com.grahambartley.synthesis.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Resolves, downloads, verifies, and extracts the per-OS external engine bundle the {@link
 * ExternalEngineClient} launches.
 *
 * <p>It fetches {@code engine-manifest.json} from the GitHub Release whose tag matches THIS build's
 * plugin version ({@code v<version>}), over HTTPS with the injected {@link OkHttpClient} (Hub rule:
 * never {@code new OkHttpClient()}), parses it with the injected {@link Gson}, resolves the current
 * OS/arch to a platform id ({@code osx-aarch64 | linux-x64 | win-x64} are the built targets),
 * downloads that artifact from its manifest {@code url}, verifies its sha256 against the manifest,
 * and extracts it under {@code ~/.runelite/voiced-dialogue/engines/<engine>-<version>/}. The shadow
 * jar and the engine bundles ship in that same matching release, so a given build always resolves
 * the engine it was released with. The archive format is inferred from the artifact filename:
 * win-x64 bundles are {@code .zip} (extracted with {@link ZipInputStream}) and
 * osx-aarch64/linux-x64 bundles are {@code .tar.gz} (extracted with the system {@code tar}). On
 * macOS it clears the {@code com.apple.quarantine} xattr on the extracted files so Gatekeeper does
 * not block an unsigned/non-notarized binary.
 *
 * <p>A platform entry carries {@code url}/{@code sha256}/{@code launcher}: the artifact is
 * downloaded, verified against its sha256, and extracted directly.
 *
 * <p>It is idempotent: a bundle already extracted with a present launcher is reused without a
 * re-download. A dev/{@code SNAPSHOT} build has no matching published release, so the manifest
 * fetch is skipped and {@link #install()} returns {@code null} (local backend unavailable), never a
 * crash. A platform with no manifest entry at all (e.g. Intel Mac, which resolves to {@code
 * osx-x64} but ships no bundle) is likewise treated as "no installable engine" -> {@code null}, not
 * an error. Any fetch/download failure or hash mismatch fails cleanly to {@code null} (backend
 * unavailable) with no partial install. All of this is blocking I/O and is expected to run off the
 * game thread (the pipeline executor, via the backend's {@code warmUp}).
 */
@Slf4j
public class EngineInstaller {

  /**
   * Committed resource holding this build's plugin version; shipped verbatim, including by the Hub.
   */
  static final String VERSION_RESOURCE = "/plugin-version.txt";

  /** Repository whose GitHub Releases host the per-version engine bundles + manifest asset. */
  static final String REPO = "grabartley/runelite-voiced-dialogue";

  /** Asset name of the engine manifest published into each release. */
  static final String MANIFEST_ASSET = "engine-manifest.json";

  private final OkHttpClient httpClient;
  private final Gson gson;
  private final Path enginesRoot;

  /** Result of a successful install: the resolved launcher path and the engine/version it backs. */
  public static final class Installed {
    private final Path launcher;
    private final String engine;
    private final String version;

    public Installed(Path launcher, String engine, String version) {
      this.launcher = launcher;
      this.engine = engine;
      this.version = version;
    }

    public Path launcher() {
      return launcher;
    }

    public String engine() {
      return engine;
    }

    public String version() {
      return version;
    }
  }

  /**
   * @param httpClient the injected OkHttp client (Hub rule: never {@code new OkHttpClient()})
   * @param gson the injected Gson
   * @param enginesRoot base dir, typically {@code ~/.runelite/voiced-dialogue/engines}
   */
  public EngineInstaller(OkHttpClient httpClient, Gson gson, Path enginesRoot) {
    this.httpClient = httpClient;
    this.gson = gson;
    this.enginesRoot = enginesRoot;
  }

  /**
   * Ensures the engine for the current platform is installed and returns its launcher, or {@code
   * null} when no installable engine is available (dev/empty manifest, unsupported platform, or a
   * download/verify failure). Idempotent.
   */
  public Installed install() {
    JsonObject manifest = readManifest();
    if (manifest == null) {
      return null;
    }
    String engine = optString(manifest, "engine", "engine");
    String version = optString(manifest, "version", "0.0.0-dev");

    String platform = currentPlatformId();
    if (platform == null) {
      log.info("No external engine build for this OS/arch; local backend unavailable.");
      return null;
    }

    JsonObject artifacts =
        manifest.has("artifacts") && manifest.get("artifacts").isJsonObject()
            ? manifest.getAsJsonObject("artifacts")
            : null;
    if (artifacts == null || !artifacts.has(platform) || !artifacts.get(platform).isJsonObject()) {
      log.info(
          "Engine manifest has no artifact for platform '{}'; local backend unavailable.",
          platform);
      return null;
    }
    JsonObject entry = artifacts.getAsJsonObject(platform);
    String launcherName = optString(entry, "launcher", null);
    String url = optString(entry, "url", "");
    String sha256 = optString(entry, "sha256", "");

    // The archive format is inferred from the artifact url filename. The engine release pipeline
    // packages win-x64 as a .zip and osx-aarch64/linux-x64 as a .tar.gz, so this must dispatch to
    // the right extractor (see #66: extracting a tar.gz as a zip silently extracts nothing).
    boolean tarGz = isTarGz(url);

    if (launcherName == null || sha256.isEmpty() || url.isEmpty()) {
      // Dev manifest placeholder: no release published yet. Not an error, just "nothing to
      // install".
      log.info(
          "Engine manifest is the dev placeholder (empty url/sha256 for '{}'); no engine release"
              + " published yet, local backend unavailable.",
          platform);
      return null;
    }

    Path installDir = enginesRoot.resolve(engine + "-" + version);

    try {
      Path existing = resolveLauncher(installDir, launcherName);
      if (existing != null) {
        log.debug("External engine already installed at {}", existing);
        makeExecutable(existing);
        return new Installed(existing, engine, version);
      }

      Files.createDirectories(installDir);
      Path archive =
          Files.createTempFile(enginesRoot, engine + "-" + version, tarGz ? ".tar.gz" : ".zip");
      try {
        download(url, archive);
        verifySha256(archive, sha256);
        if (tarGz) {
          extractTarGz(archive, installDir);
        } else {
          extractZip(archive, installDir);
        }
      } finally {
        Files.deleteIfExists(archive);
      }

      Path launcher = resolveLauncher(installDir, launcherName);
      if (launcher == null) {
        log.warn(
            "Engine bundle extracted but launcher '{}' is missing under {}",
            launcherName,
            installDir);
        return null;
      }
      makeExecutable(launcher);
      if (isMac()) {
        clearQuarantine(installDir);
      }
      log.info("Installed external engine {} {} for {} at {}", engine, version, platform, launcher);
      return new Installed(launcher, engine, version);
    } catch (IOException e) {
      log.warn("Failed to install external engine for {}: {}", platform, e.getMessage());
      return null;
    }
  }

  /**
   * Resolves the engine launcher inside an extracted bundle. Prefers the launcher at the install
   * root (the tar.gz layout the macOS/Linux bundles use), and otherwise searches a shallow subtree
   * for it. The Windows {@code .zip} bundle nests its whole tree under a single wrapper directory
   * ({@code engine-image/}), so its launcher lands one level down; finding it there lets the
   * already-published bundle work without a re-release. The launcher resolves its own siblings
   * ({@code runtime/}, {@code lib/}, {@code model/}) relative to its own location, so running it
   * from the nested directory is correct. Returns {@code null} when no launcher file is present.
   */
  private static Path resolveLauncher(Path installDir, String launcherName) throws IOException {
    Path atRoot = installDir.resolve(launcherName);
    if (Files.isRegularFile(atRoot)) {
      return atRoot;
    }
    if (!Files.isDirectory(installDir)) {
      return null;
    }
    try (Stream<Path> tree = Files.walk(installDir, 4)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().equals(launcherName))
          .findFirst()
          .orElse(null);
    }
  }

  /**
   * Fetches and parses {@code engine-manifest.json} from the GitHub Release matching this build's
   * version, or {@code null} when there is no matching release (dev/{@code SNAPSHOT} build,
   * unpublished version, or a network failure). Overridable so tests can supply a synthetic
   * manifest without the network.
   */
  protected JsonObject readManifest() {
    String version = ownVersion();
    if (version == null || version.isEmpty() || version.endsWith("-SNAPSHOT")) {
      log.info(
          "Plugin version '{}' has no matching published engine release (dev build); local backend"
              + " unavailable.",
          version);
      return null;
    }
    String url = manifestUrl(version);
    try {
      Request request = new Request.Builder().url(url).build();
      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          log.info(
              "No engine manifest for version {} (HTTP {} at {}); local backend unavailable.",
              version,
              response.code(),
              url);
          return null;
        }
        ResponseBody body = response.body();
        if (body == null) {
          return null;
        }
        try (Reader reader = new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)) {
          return gson.fromJson(reader, JsonObject.class);
        }
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not fetch engine-manifest.json from {}: {}", url, e.getMessage());
      return null;
    }
  }

  /**
   * This build's plugin version, read from the committed resource {@code /plugin-version.txt}, or
   * {@code null} if absent. Overridable in tests.
   */
  protected String ownVersion() {
    try (InputStream in = EngineInstaller.class.getResourceAsStream(VERSION_RESOURCE)) {
      if (in == null) {
        return null;
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * The {@code engine-manifest.json} release-asset URL for a plugin version (tag {@code
   * v<version>}). Overridable in tests to point at a local server.
   */
  protected String manifestUrl(String version) {
    return "https://github.com/" + REPO + "/releases/download/v" + version + "/" + MANIFEST_ASSET;
  }

  /** Downloads {@code url} to {@code target} using the injected OkHttp client. */
  private void download(String url, Path target) throws IOException {
    log.info("Downloading external engine bundle (one time): {}", url);
    Request request = new Request.Builder().url(url).build();
    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("HTTP " + response.code() + " downloading " + url);
      }
      ResponseBody body = response.body();
      if (body == null) {
        throw new IOException("empty response body for " + url);
      }
      try (InputStream in = body.byteStream()) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    log.info("Engine bundle download complete");
  }

  /** Verifies the file's sha256 hex digest against the expected value (case-insensitive). */
  private void verifySha256(Path file, String expectedHex) throws IOException {
    String actual = sha256Hex(file);
    if (!actual.equalsIgnoreCase(expectedHex.trim())) {
      throw new IOException(
          "sha256 mismatch for engine bundle: expected " + expectedHex + " but got " + actual);
    }
  }

  static String sha256Hex(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      StringBuilder sb = new StringBuilder();
      for (byte b : digest.digest()) {
        sb.append(Character.forDigit((b >> 4) & 0xf, 16));
        sb.append(Character.forDigit(b & 0xf, 16));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 not available", e);
    }
  }

  /** Extracts a zip into {@code destDir}, guarding against path-traversal entries. */
  static void extractZip(Path archive, Path destDir) throws IOException {
    Path normalizedDest = destDir.normalize();
    try (InputStream fin = Files.newInputStream(archive);
        ZipInputStream zip = new ZipInputStream(fin)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        Path resolved = normalizedDest.resolve(entry.getName()).normalize();
        if (!resolved.startsWith(normalizedDest)) {
          throw new IOException("Refusing to extract entry outside target dir: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(resolved);
        } else {
          Files.createDirectories(resolved.getParent());
          Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING);
        }
        zip.closeEntry();
      }
    }
  }

  /**
   * Returns true if the artifact filename names a gzip-compressed tarball ({@code .tar.gz} or
   * {@code .tgz}), case-insensitively. Anything else (including a {@code .zip}) is treated as a
   * zip.
   */
  static boolean isTarGz(String name) {
    if (name == null) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
  }

  /**
   * Extracts a gzip-compressed tarball into {@code destDir} by shelling out to the system {@code
   * tar} ({@code tar -xzf <archive> -C <destDir>}). The osx-aarch64/linux-x64 engine bundles ship
   * as {@code .tar.gz} (win-x64 ships as {@code .zip} and never reaches here), and {@code tar} is
   * always present on macOS and Linux. Output is drained so the process can exit; a non-zero exit
   * or a timeout throws {@link IOException}, which the caller turns into a clean {@code null}
   * (backend unavailable). The bundles are flat, so the launcher lands directly under {@code
   * destDir}.
   */
  static void extractTarGz(Path archive, Path destDir) throws IOException {
    try {
      Process p =
          new ProcessBuilder(
                  "tar", "-xzf", archive.toString(), "-C", destDir.normalize().toString())
              .redirectErrorStream(true)
              .start();
      // Drain output so the process can exit, then wait with a bounded timeout.
      try (InputStream in = p.getInputStream()) {
        byte[] buf = new byte[64 * 1024];
        while (in.read(buf) != -1) {
          // discard
        }
      }
      if (!p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
        p.destroyForcibly();
        throw new IOException("tar extraction timed out for " + archive);
      }
      int exit = p.exitValue();
      if (exit != 0) {
        throw new IOException("tar exited with status " + exit + " extracting " + archive);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while extracting " + archive, e);
    }
  }

  /** Marks the launcher executable on POSIX systems so it can be spawned directly. */
  private static void makeExecutable(Path file) {
    try {
      Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(file));
      perms.add(PosixFilePermission.OWNER_EXECUTE);
      perms.add(PosixFilePermission.GROUP_EXECUTE);
      perms.add(PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(file, perms);
    } catch (UnsupportedOperationException | IOException ignored) {
      // Non-POSIX (Windows) or unreadable perms: nothing to do.
    }
  }

  /**
   * Clears the {@code com.apple.quarantine} xattr recursively on the extracted bundle so Gatekeeper
   * does not block the unsigned/non-notarized engine binary and dylibs on first launch.
   * Best-effort: a failure here is logged, not fatal.
   */
  private static void clearQuarantine(Path dir) {
    try {
      Process p =
          new ProcessBuilder("xattr", "-dr", "com.apple.quarantine", dir.toString())
              .redirectErrorStream(true)
              .start();
      // Drain output so the process can exit, then wait briefly.
      try (InputStream in = p.getInputStream()) {
        byte[] buf = new byte[1024];
        while (in.read(buf) != -1) {
          // discard
        }
      }
      p.waitFor();
    } catch (IOException e) {
      log.debug("Could not clear quarantine xattr on {}: {}", dir, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Resolves the current OS/arch to a manifest platform id, or {@code null} if unsupported. Public
   * for unit testing.
   */
  static String currentPlatformId() {
    return platformId(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  /**
   * Pure mapping of (os.name, os.arch) to a manifest platform id; {@code null} when unsupported.
   */
  static String platformId(String osName, String osArch) {
    String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
    boolean aarch64 = arch.contains("aarch64") || arch.contains("arm64") || arch.contains("arm");
    boolean x64 = arch.contains("amd64") || arch.contains("x86_64") || arch.equals("x64");
    if (os.contains("mac") || os.contains("darwin") || os.contains("osx")) {
      // Intel Mac still resolves to osx-x64, but no osx-x64 bundle is built; install() then finds
      // no manifest entry for it and degrades to "no engine" (null) rather than crashing.
      return aarch64 ? "osx-aarch64" : "osx-x64";
    }
    if (os.contains("win")) {
      return x64 ? "win-x64" : null;
    }
    if (os.contains("linux")) {
      return x64 ? "linux-x64" : null;
    }
    return null;
  }

  private static boolean isMac() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("mac") || os.contains("darwin") || os.contains("osx");
  }

  private static String optString(JsonObject obj, String key, String fallback) {
    return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
        ? obj.get(key).getAsString()
        : fallback;
  }
}
