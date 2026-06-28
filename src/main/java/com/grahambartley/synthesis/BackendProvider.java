package com.grahambartley.synthesis;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.TTSDialogueConfig.VoiceBackend;
import com.grahambartley.tts.Pcm;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Single point of truth for which {@link SynthesisBackend} is active and how emotion is downgraded.
 *
 * <p>The active backend is resolved from {@link TTSDialogueConfig#voiceBackend()} on every call, so
 * switching the backend in the config panel takes effect immediately without a client restart. The
 * two backends are kept strictly separate: the selected backend is the only one that ever runs.
 * Cloud means cloud only and Local means local only; there is no cross-backend fallback. A line the
 * selected backend cannot voice is simply not voiced.
 *
 * <p>The emotion-downgrade rule lives here and nowhere else: {@link #synthesize} rewrites a
 * request's emotion to {@link Emotion#NEUTRAL} whenever the active backend does not list it in
 * {@link SynthesisBackend#supportedEmotions()}, so individual backends never have to special-case
 * emotions they cannot voice.
 */
@Slf4j
public final class BackendProvider {

  /** Id of the offline Kokoro backend, selected when Voice Backend is Local. */
  public static final String LOCAL_KOKORO_ID = "local-kokoro";

  private final TTSDialogueConfig config;
  private final Map<String, SynthesisBackend> backends = new LinkedHashMap<>();

  /**
   * @param config the live plugin config, read on every resolution so runtime switches apply
   * @param backendList the registered backends; one of them must have id {@code local-kokoro} so
   *     the Local selection always resolves
   */
  public BackendProvider(TTSDialogueConfig config, SynthesisBackend... backendList) {
    this.config = config;
    boolean hasLocalKokoro = false;
    for (SynthesisBackend backend : backendList) {
      backends.put(backend.id(), backend);
      if (LOCAL_KOKORO_ID.equals(backend.id())) {
        hasLocalKokoro = true;
      }
    }
    if (!hasLocalKokoro) {
      throw new IllegalArgumentException(
          "BackendProvider requires a '" + LOCAL_KOKORO_ID + "' backend");
    }
  }

  /** Maps the config selection to a backend id. */
  private static String backendIdFor(VoiceBackend selection) {
    switch (selection) {
      case CLOUD:
        return OpenRouterTtsBackend.ID;
      case LOCAL:
      default:
        return LOCAL_KOKORO_ID;
    }
  }

  /**
   * Resolves the backend the config currently selects. The selection is authoritative: an
   * unavailable selected backend is still returned (its lines stay silent) rather than swapped for
   * the other backend, so Cloud and Local never bleed into each other.
   */
  public SynthesisBackend active() {
    return backends.get(backendIdFor(config.voiceBackend()));
  }

  /**
   * Applies the emotion-downgrade rule for a backend: if the backend cannot voice the request's
   * emotion, the emotion is rewritten to {@link Emotion#NEUTRAL}. This is the single definition of
   * the rule, shared by {@link #synthesize} and the pipeline's cache-key computation.
   */
  public static SynthesisRequest downgradeFor(SynthesisBackend backend, SynthesisRequest request) {
    if (backend.supportedEmotions().contains(request.emotion())) {
      return request;
    }
    return request.withEmotion(Emotion.NEUTRAL);
  }

  /**
   * Convenience entry that resolves {@link #active()} and synthesizes in one call, applying the
   * emotion-downgrade rule first so the backend only ever receives an emotion it supports. Returns
   * {@code null} on failure. Use this only when cache-key parity does not matter (e.g. tests); the
   * pipeline instead resolves {@link #active()} itself and calls {@link #synthesizeWith} so the
   * backend reflected in the cache key is the one that actually runs.
   */
  public Pcm synthesize(SynthesisRequest request) {
    SynthesisBackend backend = active();
    return backend.synthesize(downgradeFor(backend, request));
  }

  /**
   * Synthesizes through a specific, already-resolved backend. Used by the pipeline so the backend
   * chosen when a line is enqueued matches the backend reflected in its cache key.
   */
  public Pcm synthesizeWith(SynthesisBackend backend, SynthesisRequest request) {
    return backend.synthesize(downgradeFor(backend, request));
  }

  /**
   * Warms up the selected backend on the pipeline thread, so a backend gets its off-thread
   * install/spawn/handshake (or model load) before the first line. Only the selected backend is
   * touched: selecting Cloud never warms the local engine, and selecting Local never reaches the
   * cloud. Safe to call repeatedly: each backend's {@code warmUp} is idempotent.
   */
  public void warmUpActive() {
    SynthesisBackend wanted = active();
    if (wanted != null) {
      wanted.warmUp();
    }
  }

  /** Releases every registered backend. */
  public void close() {
    for (SynthesisBackend backend : backends.values()) {
      try {
        backend.close();
      } catch (RuntimeException e) {
        log.debug("Error closing backend {}: {}", backend.id(), e.getMessage());
      }
    }
  }
}
