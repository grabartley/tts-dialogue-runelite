package com.grahambartley.synthesis.engine;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.gson.Gson;
import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import com.grahambartley.tts.Pcm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * End-to-end round-trip against the REAL locally-built engine image, spawning the actual {@code
 * --stdio} process. It is gated on the presence of {@code engine-kokoro/build/engine-image} (and a
 * usable model), so CI and dev machines without a built engine skip it cleanly via JUnit {@code
 * assume} instead of failing. This is the only test that proves the spawn -> request -> PCM path
 * against the production engine; the framing logic itself is covered without a real process by
 * {@link ExternalEngineClientTest}.
 *
 * <p>Model resolution: the engine reads {@code KOKORO_MODEL_DIR} or a {@code model/} dir beside the
 * image. Set {@code KOKORO_MODEL_DIR} (it is inherited by the spawned child) to point at an
 * extracted {@code kokoro-multi-lang-v1_0} when running locally.
 */
public class ExternalEngineRealIntegrationTest {

  private static Path launcher() {
    String os = System.getProperty("os.name", "").toLowerCase();
    String name = os.contains("win") ? "kokoro-engine.bat" : "kokoro-engine";
    // Resolve from the repo root regardless of the test's working dir.
    Path candidate = Paths.get("engine-kokoro", "build", "engine-image", name).toAbsolutePath();
    if (Files.isRegularFile(candidate)) {
      return candidate;
    }
    return Paths.get("..", "..", "..", "engine-kokoro", "build", "engine-image", name)
        .toAbsolutePath();
  }

  private static boolean modelResolvable() {
    String env = System.getenv("KOKORO_MODEL_DIR");
    if (env != null && !env.isEmpty()) {
      Path d = Paths.get(env);
      return Files.isRegularFile(d.resolve("model.onnx"));
    }
    Path beside = launcher().getParent().resolve("model").resolve("model.onnx");
    return Files.isRegularFile(beside);
  }

  @Test
  public void realEngineRoundTripProducesPcm() {
    Path launcher = launcher();
    assumeTrue(
        "engine image not built (run :engine-kokoro:engineImage) - skipping real round-trip",
        Files.isRegularFile(launcher));
    assumeTrue(
        "no Kokoro model resolvable (set KOKORO_MODEL_DIR) - skipping real round-trip",
        modelResolvable());

    ExternalEngineClient client = new ExternalEngineClient(launcher, new Gson());
    try {
      SynthesisRequest request =
          new SynthesisRequest(
              "Conformance check from the plugin transport.",
              VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE),
              Emotion.NEUTRAL);
      Pcm pcm = client.synthesize(request);
      assertNotNull("real engine should return PCM", pcm);
      assertTrue("sample rate must be positive", pcm.getSampleRate() > 0);
      assertTrue("must produce audio samples", pcm.getSamples().length > 0);

      // A second request on the same long-lived process proves lifecycle reuse.
      Pcm second = client.synthesize(request);
      assertNotNull("second request on the same process should also work", second);
      assertTrue(second.getSamples().length > 0);
    } finally {
      client.stop();
    }
  }
}
