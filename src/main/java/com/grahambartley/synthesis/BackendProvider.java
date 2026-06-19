package com.grahambartley.synthesis;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.TTSDialogueConfig.VoiceBackend;
import com.grahambartley.tts.Pcm;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Single point of truth for which {@link SynthesisBackend} is active and how emotion is downgraded.
 *
 * <p>The active backend is resolved from {@link TTSDialogueConfig#voiceBackend()} on every call, so
 * switching the backend in the config panel takes effect immediately without a client restart. When
 * the selected backend reports {@link SynthesisBackend#isAvailable()} {@code false} (engine
 * missing, key unset, GPU absent) the provider transparently falls back to the local Kokoro backend
 * so the plugin keeps speaking, surfacing that fallback once through an optional notice hook.
 *
 * <p>The emotion-downgrade rule lives here and nowhere else: {@link #synthesize} rewrites a
 * request's emotion to {@link Emotion#NEUTRAL} whenever the active backend does not list it in
 * {@link SynthesisBackend#supportedEmotions()}, so individual backends never have to special-case
 * emotions they cannot voice.
 */
@Slf4j
public final class BackendProvider {

  /** The backend used whenever the selected one is unavailable. */
  public static final String LOCAL_KOKORO_ID = "local-kokoro";

  private final TTSDialogueConfig config;
  private final Map<String, SynthesisBackend> backends = new LinkedHashMap<>();
  private final SynthesisBackend localKokoro;

  /** Notice hook fired once when a configured backend is unavailable and we fall back. */
  private Consumer<String> availabilityNotice = msg -> {};

  /**
   * Tracks which configured backends we have already warned about so the notice fires once each.
   */
  private String lastFallbackFrom;

  /**
   * @param config the live plugin config, read on every resolution so runtime switches apply
   * @param backendList the registered backends; the one with id {@code local-kokoro} is the
   *     fallback
   */
  public BackendProvider(TTSDialogueConfig config, SynthesisBackend... backendList) {
    this.config = config;
    SynthesisBackend kokoro = null;
    for (SynthesisBackend backend : backendList) {
      backends.put(backend.id(), backend);
      if (LOCAL_KOKORO_ID.equals(backend.id())) {
        kokoro = backend;
      }
    }
    if (kokoro == null) {
      throw new IllegalArgumentException(
          "BackendProvider requires a '" + LOCAL_KOKORO_ID + "' backend");
    }
    this.localKokoro = kokoro;
  }

  /** Registers a one-time notice hook (e.g. a chat or log message) for backend fallbacks. */
  public void setAvailabilityNotice(Consumer<String> notice) {
    this.availabilityNotice = notice == null ? msg -> {} : notice;
  }

  /** Maps the config selection to a backend id. */
  private static String backendIdFor(VoiceBackend selection) {
    switch (selection) {
      case LOCAL_GPU:
        return "local-zonos";
      case CLOUD:
        return "cloud-azure";
      case LOCAL:
      default:
        return LOCAL_KOKORO_ID;
    }
  }

  /**
   * Resolves the active backend from config, falling back to local Kokoro when the selected backend
   * is missing or unavailable.
   */
  public SynthesisBackend active() {
    String wantedId = backendIdFor(config.voiceBackend());
    SynthesisBackend wanted = backends.get(wantedId);
    if (wanted != null && wanted.isAvailable()) {
      lastFallbackFrom = null;
      return wanted;
    }
    if (!LOCAL_KOKORO_ID.equals(wantedId) && !wantedId.equals(lastFallbackFrom)) {
      lastFallbackFrom = wantedId;
      String msg =
          "Voice backend '" + wantedId + "' is not available; using the local voice instead.";
      log.info(msg);
      availabilityNotice.accept(msg);
    }
    return localKokoro;
  }

  /**
   * Applies the emotion-downgrade rule for a backend: if the backend cannot voice the request's
   * emotion, the emotion is rewritten to {@link Emotion#NEUTRAL}. This is the single definition of
   * the rule, shared by {@link #synthesize} and the pipeline's cache-key computation.
   */
  public static SynthesisRequest downgradeFor(SynthesisBackend backend, SynthesisRequest request) {
    if (backend.supportedEmotions().contains(request.getEmotion())) {
      return request;
    }
    return request.withEmotion(Emotion.NEUTRAL);
  }

  /**
   * Synthesizes the request through the active backend, applying the emotion-downgrade rule first
   * so the backend only ever receives an emotion it supports. Returns {@code null} on failure.
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

  /** Warms up the local Kokoro backend (model load) on the pipeline thread. */
  public void warmUpLocal() {
    localKokoro.warmUp();
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
