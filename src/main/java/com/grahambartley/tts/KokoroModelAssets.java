package com.grahambartley.tts;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * Manages the Kokoro model bundle on disk.
 *
 * <p>The bundle is ~349 MB compressed, so it is not committed to the repo. It is downloaded once
 * from the sherpa-onnx release assets and extracted into the RuneLite data directory. Synthesis
 * itself never touches the network; only this first-run setup does.
 */
@Slf4j
public class KokoroModelAssets {

  public static final String MODEL_NAME = "kokoro-multi-lang-v1_0";

  private static final String DOWNLOAD_URL =
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
          + MODEL_NAME
          + ".tar.bz2";

  private final Path baseDir;

  public KokoroModelAssets(Path baseDir) {
    this.baseDir = baseDir;
  }

  public Path modelDir() {
    return baseDir.resolve(MODEL_NAME);
  }

  /** True once the core model files are present on disk. */
  public boolean isExtracted() {
    Path dir = modelDir();
    return Files.isRegularFile(dir.resolve("model.onnx"))
        && Files.isRegularFile(dir.resolve("voices.bin"))
        && Files.isRegularFile(dir.resolve("tokens.txt"));
  }

  /** Ensures the bundle is present, downloading and extracting it on first run. */
  public synchronized Path ensureAvailable() throws IOException {
    Path modelDir = modelDir();
    if (isExtracted()) {
      return modelDir;
    }

    Files.createDirectories(baseDir);
    Path archive = baseDir.resolve(MODEL_NAME + ".tar.bz2");
    download(DOWNLOAD_URL, archive);
    extract(archive, baseDir);
    Files.deleteIfExists(archive);

    if (!isExtracted()) {
      throw new IOException(
          "Kokoro bundle extracted but expected files are missing in " + modelDir);
    }
    return modelDir;
  }

  private void download(String url, Path target) throws IOException {
    log.info("Downloading Kokoro model bundle (~349 MB), this happens once: {}", url);
    Path part = Files.createTempFile(baseDir, MODEL_NAME, ".tar.bz2.part");
    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
    con.setInstanceFollowRedirects(true);
    con.setConnectTimeout(15_000);
    con.setReadTimeout(60_000);
    try (InputStream in = con.getInputStream()) {
      Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      con.disconnect();
    }
    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    log.info("Kokoro model bundle download complete");
  }

  private void extract(Path archive, Path destDir) throws IOException {
    log.info("Extracting Kokoro model bundle into {}", destDir);
    try (InputStream fin = Files.newInputStream(archive);
        BZip2CompressorInputStream bz = new BZip2CompressorInputStream(fin, true);
        TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        Path outPath = safeResolve(destDir, entry.getName());
        if (entry.isDirectory()) {
          Files.createDirectories(outPath);
        } else {
          Files.createDirectories(outPath.getParent());
          Files.copy(tar, outPath, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    log.info("Kokoro model bundle extraction complete");
  }

  /** Guards against path-traversal entries when extracting the archive. */
  private Path safeResolve(Path destDir, String entryName) throws IOException {
    Path normalizedDest = destDir.normalize();
    Path resolved = normalizedDest.resolve(entryName).normalize();
    if (!resolved.startsWith(normalizedDest)) {
      throw new IOException("Refusing to extract entry outside target dir: " + entryName);
    }
    return resolved;
  }
}
