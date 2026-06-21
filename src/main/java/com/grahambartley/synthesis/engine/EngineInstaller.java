package com.grahambartley.synthesis.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * <p>It reads the bundled {@code /engine-manifest.json} resource (produced by issue #36) with the
 * injected {@link Gson}, resolves the current OS/arch to a platform id ({@code osx-aarch64 |
 * linux-x64 | win-x64} are the built targets), downloads that artifact from its manifest {@code
 * url} with the injected {@link OkHttpClient} (Hub rule: never {@code new OkHttpClient()}),
 * verifies its sha256 against the manifest, and extracts it under {@code
 * ~/.runelite/tts-dialogue/engines/<engine>-<version>/}. On macOS it clears the {@code
 * com.apple.quarantine} xattr on the extracted files so Gatekeeper does not block an
 * unsigned/non-notarized binary.
 *
 * <p>A platform entry is one of two shapes, distinguished by the presence of a {@code parts} array
 * (issue #60). A single-file entry carries {@code url}/{@code sha256} and is downloaded, verified,
 * and extracted directly (Kokoro, and any Zonos bundle under GitHub's 2 GiB asset cap). A split
 * entry carries an ordered {@code parts} list (each {@code url}/{@code sha256}/{@code size}) plus a
 * combined {@code sha256} of the reassembled archive (the ~2.97 GB Zonos bundle, which exceeds the
 * cap): each part is downloaded and verified, concatenated in order into the final {@code .zip},
 * the combined sha256 is verified, and the same extraction path runs. The single-file path is
 * unchanged.
 *
 * <p>It is idempotent: a bundle already extracted with a present launcher is reused without a
 * re-download. The dev manifest ships empty urls/sha256 (a real release has not been published
 * yet); that case is treated as "no installable engine" and surfaces as {@link #install()}
 * returning {@code null}, never a crash. A platform with no manifest entry at all (e.g. Intel Mac,
 * which resolves to {@code osx-x64} but ships no bundle) is likewise treated as "no installable
 * engine" -> {@code null}, not an error. Any missing/short/corrupt part or hash mismatch fails
 * cleanly to {@code null} (backend unavailable) with no partial install. All of this is blocking
 * I/O and is expected to run off the game thread (the pipeline executor, via the backend's {@code
 * warmUp}).
 */
@Slf4j
public class EngineInstaller {

  /** The Kokoro engine manifest, the default for the bare three-arg constructor. */
  public static final String KOKORO_MANIFEST_RESOURCE = "/engine-manifest.json";

  /** The Zonos engine manifest, a separate artifact resolved through the same installer. */
  public static final String ZONOS_MANIFEST_RESOURCE = "/zonos-engine-manifest.json";

  private final OkHttpClient httpClient;
  private final Gson gson;
  private final Path enginesRoot;
  private final String manifestResource;

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
   * @param enginesRoot base dir, typically {@code ~/.runelite/tts-dialogue/engines}
   */
  public EngineInstaller(OkHttpClient httpClient, Gson gson, Path enginesRoot) {
    this(httpClient, gson, enginesRoot, KOKORO_MANIFEST_RESOURCE);
  }

  /**
   * Same installer, pointed at a specific engine manifest resource so a second engine (Zonos) can
   * be resolved through the identical download/verify/extract path without a second installer
   * class.
   *
   * @param manifestResource classpath resource, e.g. {@link #KOKORO_MANIFEST_RESOURCE} or {@link
   *     #ZONOS_MANIFEST_RESOURCE}
   */
  public EngineInstaller(
      OkHttpClient httpClient, Gson gson, Path enginesRoot, String manifestResource) {
    this.httpClient = httpClient;
    this.gson = gson;
    this.enginesRoot = enginesRoot;
    this.manifestResource = manifestResource;
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
    JsonArray parts =
        entry.has("parts") && entry.get("parts").isJsonArray()
            ? entry.getAsJsonArray("parts")
            : null;
    boolean split = parts != null && parts.size() > 0;

    // A split entry carries the combined sha256 (of the reassembled archive); a single-file entry
    // carries the artifact url + sha256. The launcher is required either way.
    String url = optString(entry, "url", "");
    String sha256 = optString(entry, "sha256", "");

    if (launcherName == null || sha256.isEmpty() || (!split && url.isEmpty())) {
      // Dev manifest placeholder: no release published yet. Not an error, just "nothing to
      // install".
      log.info(
          "Engine manifest is the dev placeholder (empty url/sha256 for '{}'); no engine release"
              + " published yet, local backend unavailable.",
          platform);
      return null;
    }

    Path installDir = enginesRoot.resolve(engine + "-" + version);
    Path launcher = installDir.resolve(launcherName);

    try {
      if (Files.isRegularFile(launcher)) {
        log.debug("External engine already installed at {}", launcher);
        makeExecutable(launcher);
        return new Installed(launcher, engine, version);
      }

      Files.createDirectories(installDir);
      Path archive = Files.createTempFile(enginesRoot, engine + "-" + version, ".zip");
      try {
        if (split) {
          assembleParts(parts, archive);
        } else {
          download(url, archive);
        }
        verifySha256(archive, sha256);
        extractZip(archive, installDir);
      } finally {
        Files.deleteIfExists(archive);
      }

      if (!Files.isRegularFile(launcher)) {
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
   * Reads and parses the bundled {@code /engine-manifest.json} resource, or {@code null} if
   * absent/unparseable. Overridable so tests can supply a synthetic manifest without touching the
   * committed dev resource.
   */
  protected JsonObject readManifest() {
    try (InputStream in = EngineInstaller.class.getResourceAsStream(manifestResource)) {
      if (in == null) {
        log.warn("engine manifest resource {} not found on classpath", manifestResource);
        return null;
      }
      try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        return gson.fromJson(reader, JsonObject.class);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read engine-manifest.json: {}", e.getMessage());
      return null;
    }
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

  /**
   * Downloads each split part via the injected client, verifies its sha256, and concatenates the
   * parts IN ORDER into {@code archive} (streamed, so the ~2.97 GB reassembled bundle never sits in
   * memory). Each part is fetched to a sibling temp file, hash-checked, appended, then deleted, so
   * a failure mid-way leaves no large stray files. Any missing field, failed download, or per-part
   * hash mismatch throws {@link IOException}; the caller turns that into a clean {@code null}
   * (backend unavailable) and deletes the partial archive. The combined sha256 is verified by the
   * caller after assembly.
   */
  private void assembleParts(JsonArray parts, Path archive) throws IOException {
    log.info("Downloading external engine bundle in {} part(s) (one time)", parts.size());
    List<Path> tempParts = new ArrayList<>();
    // The archive temp file was just created (empty); newOutputStream truncates by default.
    try (OutputStream out = Files.newOutputStream(archive)) {
      for (int i = 0; i < parts.size(); i++) {
        if (!parts.get(i).isJsonObject()) {
          throw new IOException("malformed engine manifest part at index " + i);
        }
        JsonObject part = parts.get(i).getAsJsonObject();
        String partUrl = optString(part, "url", "");
        String partSha = optString(part, "sha256", "");
        if (partUrl.isEmpty() || partSha.isEmpty()) {
          throw new IOException("engine manifest part " + i + " is missing url/sha256");
        }
        Path partFile = Files.createTempFile(enginesRoot, archive.getFileName() + ".part" + i, "");
        tempParts.add(partFile);
        download(partUrl, partFile);
        verifySha256(partFile, partSha);
        try (InputStream in = Files.newInputStream(partFile)) {
          byte[] buffer = new byte[64 * 1024];
          int read;
          while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
          }
        }
        Files.deleteIfExists(partFile);
        tempParts.remove(partFile);
      }
    } finally {
      // Best-effort cleanup of any part temp file left behind by a mid-assembly failure.
      for (Path p : tempParts) {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // leave it; the OS temp sweep will reclaim it
        }
      }
    }
    log.info("Engine bundle reassembled from {} part(s)", parts.size());
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
