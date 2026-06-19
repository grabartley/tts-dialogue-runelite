package com.grahambartley.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the {@code kokoro-multi-lang-v1_0} model directory the engine feeds to sherpa-onnx.
 *
 * <p>The release bundle ships the model alongside the engine, so resolution is purely local: no
 * network access ever happens at synthesis time. The lookup order is, in priority:
 *
 * <ol>
 *   <li>the {@code KOKORO_MODEL_DIR} environment variable (used by the conformance test and any
 *       custom layout),
 *   <li>the {@code model/} directory next to the engine image (the shipped bundle layout).
 * </ol>
 *
 * The first location that contains the three core model files wins; otherwise resolution fails with
 * an explicit error naming both checked locations.
 */
final class ModelLocator {

  static final String MODEL_NAME = "kokoro-multi-lang-v1_0";

  private ModelLocator() {}

  /** Resolves the model directory, or throws if no candidate contains the core files. */
  static Path resolve() {
    String env = System.getenv("KOKORO_MODEL_DIR");
    if (env != null && !env.isEmpty()) {
      Path p = Paths.get(env);
      if (hasModel(p)) {
        return p;
      }
      throw new IllegalStateException(
          "KOKORO_MODEL_DIR=" + env + " does not contain the Kokoro model files");
    }

    Path imageModel = imageDir().resolve("model");
    if (hasModel(imageModel)) {
      return imageModel;
    }
    throw new IllegalStateException(
        "Could not locate the "
            + MODEL_NAME
            + " model. Checked KOKORO_MODEL_DIR (unset or empty) and the bundled image model at '"
            + imageModel
            + "'. Set KOKORO_MODEL_DIR or ship a 'model' directory beside the engine image.");
  }

  /** Directory the running engine jar lives in, used as the anchor for the bundled model. */
  private static Path imageDir() {
    try {
      Path codeSource =
          Paths.get(ModelLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path dir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
      // Jars live under <image>/lib; the model sits at <image>/model, one level up from lib.
      if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equals("lib")) {
        return dir.getParent();
      }
      return dir != null ? dir : Paths.get("").toAbsolutePath();
    } catch (Exception e) {
      return Paths.get("").toAbsolutePath();
    }
  }

  private static boolean hasModel(Path dir) {
    return dir != null
        && Files.isRegularFile(dir.resolve("model.onnx"))
        && Files.isRegularFile(dir.resolve("voices.bin"))
        && Files.isRegularFile(dir.resolve("tokens.txt"));
  }
}
