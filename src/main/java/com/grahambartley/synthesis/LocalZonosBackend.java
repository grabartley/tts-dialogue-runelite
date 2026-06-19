package com.grahambartley.synthesis;

import com.grahambartley.synthesis.engine.EngineInstaller;
import com.grahambartley.synthesis.engine.ExternalEngineClient;
import com.grahambartley.tts.Pcm;
import java.nio.file.Path;
import java.util.EnumSet;
import lombok.extern.slf4j.Slf4j;

/**
 * The local emotional backend: Zonos-v0.1 (Apache-2.0) exposed as a {@link SynthesisBackend},
 * reached through the same external {@code --stdio} transport the Kokoro backend uses ({@link
 * ExternalEngineClient}) rather than a duplicate transport. Selected when {@code voiceBackend} is
 * {@code LOCAL_GPU}; it gives true offline emotion for users with a supported GPU.
 *
 * <p>Unlike Kokoro this backend advertises the full emotion set and renders it: each {@link
 * Emotion} is mapped, plugin-side, to a Zonos 8-dim emotion-conditioning vector ({@link
 * ZonosEmotionVectors}) that rides the request through the transport. The {@link VoiceSpec} is
 * mapped to a Zonos reference-voice id ({@link ZonosVoiceMap}); both maps live in the plugin so
 * they stay unit-testable without a GPU.
 *
 * <p><b>GPU-gated availability.</b> There is no reliable JVM-side CUDA probe, so GPU detection is
 * delegated to the engine: on {@link #warmUp()} the backend installs the per-OS Zonos engine bundle
 * (a separate artifact from Kokoro, resolved from {@code zonos-engine-manifest.json}), spawns it,
 * and runs a health handshake. {@link #isAvailable()} is true only when the engine installs,
 * spawns, and its handshake reports both ready and a usable GPU. The committed manifest is the dev
 * placeholder (no Zonos engine published yet), so install returns nothing and the backend is
 * unavailable, letting {@link BackendProvider} fall back to {@code local-kokoro} with a one-time
 * notice instead of crashing. The same fallback covers a machine with no GPU.
 *
 * <p>The engine is spawned lazily on {@link #warmUp()} (run off the game thread on the pipeline
 * executor) and torn down on {@link #close()} / switch-away / plugin shutdown. Synthesis never
 * throws and never blocks the game thread; a failure returns {@code null}.
 */
@Slf4j
public final class LocalZonosBackend implements SynthesisBackend {

  /** Stable backend id, matched by {@link BackendProvider} when {@code LOCAL_GPU} is selected. */
  public static final String ID = "local-zonos";

  private final EngineInstaller installer;
  private final ClientFactory clientFactory;
  private final ZonosVoiceMap voiceMap = new ZonosVoiceMap();

  private volatile ExternalEngineClient client;
  private volatile boolean installAttempted;

  /** Whether the engine's handshake reported a usable GPU. */
  private volatile boolean gpuPresent;

  /** Seam so tests can supply a fake transport without spawning a real process. */
  public interface ClientFactory {
    ExternalEngineClient create(Path launcher);
  }

  public LocalZonosBackend(EngineInstaller installer, ClientFactory clientFactory) {
    this.installer = installer;
    this.clientFactory = clientFactory;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public EnumSet<Emotion> supportedEmotions() {
    return EnumSet.of(Emotion.NEUTRAL, Emotion.HAPPY, Emotion.SAD, Emotion.ANGRY, Emotion.SCARED);
  }

  /**
   * Available only once warm-up has brought up a healthy engine that reported a usable GPU. Reports
   * {@code false} before warm-up (so the provider does not route GPU lines to an engine that has
   * not started yet) and after a failed install/spawn/handshake, so {@link BackendProvider} falls
   * back to {@code local-kokoro} with a one-time notice rather than producing no audio.
   */
  @Override
  public boolean isAvailable() {
    ExternalEngineClient c = client;
    return c != null && c.isHealthy() && gpuPresent;
  }

  @Override
  public synchronized void warmUp() {
    if (client != null || installAttempted) {
      return;
    }
    installAttempted = true;
    EngineInstaller.Installed installed = installer.install();
    if (installed == null) {
      log.info(
          "Local Zonos engine is not installed (no GPU engine published / unsupported platform);"
              + " backend unavailable, provider will fall back to the local voice.");
      return;
    }
    ExternalEngineClient c = clientFactory.create(installed.launcher());
    try {
      c.start();
      ExternalEngineClient.Health health = c.handshake();
      if (health == null || !health.ok()) {
        log.warn("Local Zonos engine started but did not report healthy; backend unavailable.");
        c.stop();
        return;
      }
      if (!health.gpu()) {
        log.info(
            "Local Zonos engine is healthy but reported no usable GPU ({}); emotional voices need a"
                + " supported GPU, falling back to the local voice.",
            health.detail());
        c.stop();
        return;
      }
      this.client = c;
      this.gpuPresent = true;
      log.info("Local Zonos engine warmed up with GPU: {}", installed.launcher());
    } catch (Exception e) {
      log.warn("Local Zonos engine failed to start: {}", e.getMessage());
      try {
        c.stop();
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
  }

  @Override
  public Pcm synthesize(SynthesisRequest request) {
    ExternalEngineClient c = client;
    if (c == null) {
      // First use before warm-up completed: bring the engine up now. This runs on the pipeline
      // thread (never the game thread), so the blocking install/spawn/handshake is safe here.
      warmUp();
      c = client;
      if (c == null) {
        return null;
      }
    }
    float[] emotionVector = ZonosEmotionVectors.forEmotion(request.emotion());
    return c.synthesize(request, emotionVector);
  }

  @Override
  public synchronized void close() {
    ExternalEngineClient c = client;
    if (c != null) {
      c.stop();
      client = null;
    }
    gpuPresent = false;
    // Allow a later warm-up to retry install/handshake after a switch-away then switch-back.
    installAttempted = false;
  }
}
