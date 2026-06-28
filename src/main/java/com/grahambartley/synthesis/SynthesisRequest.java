package com.grahambartley.synthesis;

/**
 * One line to synthesize: the text, the resolved {@link VoiceSpec}, the desired {@link Emotion},
 * and an optional {@link CharacterProfile} steering delivery.
 *
 * <p>This is the single unit that flows from the dialogue pipeline into a {@link SynthesisBackend}.
 * The emotion is the <em>requested</em> emotion; {@link BackendProvider} may downgrade it to {@link
 * Emotion#NEUTRAL} for backends that cannot voice it before {@link SynthesisBackend#synthesize} is
 * called.
 *
 * <p>The profile is the resolved per-speaker delivery template ({@link CharacterProfile}); it is
 * {@code null} when character profiles are off or no profile applies. Only the cloud backend
 * renders it (as a leading AUDIO PROFILE block); the local backend ignores it. A {@code null}
 * profile leaves the request body byte-for-byte as before, so existing cache entries stay valid.
 */
public record SynthesisRequest(
    String text, VoiceSpec voice, Emotion emotion, CharacterProfile profile) {

  /** A request with no character profile (backward-compatible 3-arg form). */
  public SynthesisRequest(String text, VoiceSpec voice, Emotion emotion) {
    this(text, voice, emotion, null);
  }

  /**
   * Returns a copy of this request with a different emotion, leaving text, voice, profile intact.
   */
  public SynthesisRequest withEmotion(Emotion newEmotion) {
    if (newEmotion == emotion) {
      return this;
    }
    return new SynthesisRequest(text, voice, newEmotion, profile);
  }
}
