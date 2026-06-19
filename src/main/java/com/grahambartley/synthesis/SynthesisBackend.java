package com.grahambartley.synthesis;

import com.grahambartley.tts.Pcm;
import java.util.EnumSet;

/**
 * A single text-to-speech engine the plugin can synthesize through.
 *
 * <p>Generalizes the older {@code Synthesizer} seam: every synthesis flow goes through a backend,
 * so local Kokoro, local Zonos, and cloud Azure are interchangeable. {@link BackendProvider} owns
 * selection and the emotion-downgrade rule, so an implementation only has to render the emotions it
 * advertises in {@link #supportedEmotions()}; it never receives an unsupported emotion.
 */
public interface SynthesisBackend {

  /**
   * Stable identifier, e.g. {@code "local-kokoro"}, {@code "local-zonos"}, {@code "cloud-azure"}.
   */
  String id();

  /** Whether this backend can actually run right now (engine installed, key set, GPU present). */
  boolean isAvailable();

  /** The emotions this backend can voice. Requests outside this set are downgraded to neutral. */
  EnumSet<Emotion> supportedEmotions();

  /** Synthesizes the request to PCM, or returns {@code null} on failure. */
  Pcm synthesize(SynthesisRequest request);

  /** Optional one-off warm-up (e.g. model load) run on the pipeline thread before first use. */
  default void warmUp() {}

  /** Optional resource release when the backend is torn down. */
  default void close() {}
}
