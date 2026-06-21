package com.grahambartley.synthesis;

import com.grahambartley.synthesis.engine.EngineInstaller;
import com.grahambartley.synthesis.engine.ExternalEngineClient;
import com.grahambartley.tts.Pcm;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.function.Supplier;
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

  /**
   * Supplies the raw {@code playerVoiceClipPath} config string on demand (read live so a config
   * edit applies without a restart). The custom-clip override is Zonos-only and player-only, so the
   * supplier lives on this backend and nowhere else.
   */
  private final Supplier<String> rawPlayerClipPath;

  /** Validates the raw config path into a usable absolute clip path or {@code null} (default). */
  private final PlayerVoiceClip playerClip;

  private volatile ExternalEngineClient client;
  private volatile boolean installAttempted;

  /** Whether the engine's handshake reported a usable GPU. */
  private volatile boolean gpuPresent;

  /** Seam so tests can supply a fake transport without spawning a real process. */
  public interface ClientFactory {
    ExternalEngineClient create(Path launcher);
  }

  /**
   * Backwards-compatible constructor with no custom player clip: the player always uses the bundled
   * default reference. Used by tests that do not exercise the clip path.
   */
  public LocalZonosBackend(EngineInstaller installer, ClientFactory clientFactory) {
    this(installer, clientFactory, () -> "", new PlayerVoiceClip());
  }

  public LocalZonosBackend(
      EngineInstaller installer,
      ClientFactory clientFactory,
      Supplier<String> rawPlayerClipPath,
      PlayerVoiceClip playerClip) {
    this.installer = installer;
    this.clientFactory = clientFactory;
    this.rawPlayerClipPath = rawPlayerClipPath == null ? () -> "" : rawPlayerClipPath;
    this.playerClip = playerClip == null ? new PlayerVoiceClip() : playerClip;
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
    // The custom player reference is Zonos-only (this backend) and player-only: it rides the
    // request
    // only for player-voice lines and is absent (null) for every NPC line, so NPC voices keep using
    // the bundled bank. An empty/invalid config path resolves to null here, falling the engine back
    // to the default player reference.
    String playerReferenceClip = resolvePlayerReferenceClip(request);
    return c.synthesize(request, emotionVector, playerReferenceClip);
  }

  /**
   * Resolves the custom player reference clip path for this request, or {@code null} when none
   * applies. Returns {@code null} for any NPC line (the override is player-only) and for an
   * empty/invalid configured path (fall back to the default player reference). Player + Zonos +
   * valid clip is the only case that yields a path.
   */
  private String resolvePlayerReferenceClip(SynthesisRequest request) {
    if (request.voice() == null || !request.voice().player()) {
      return null;
    }
    return playerClip.resolve(rawPlayerClipPath.get());
  }

  /**
   * Folds the custom player clip identity into the cache key for player + Zonos lines so a line
   * cloned from a user clip never serves, or is served by, the default-player-voice cache entry.
   * Returns {@code ""} for NPC lines and when no custom clip is configured, so those keys are
   * unchanged.
   */
  @Override
  public String cacheVariant(SynthesisRequest request) {
    String clip = resolvePlayerReferenceClip(request);
    if (clip == null) {
      return "";
    }
    return "clip:" + Integer.toHexString(clip.hashCode());
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
