package com.grahambartley.synthesis;

/**
 * One line to synthesize: the text, the resolved {@link VoiceSpec}, and the desired {@link
 * Emotion}.
 *
 * <p>This is the single unit that flows from the dialogue pipeline into a {@link SynthesisBackend}.
 * The emotion is the <em>requested</em> emotion; {@link BackendProvider} may downgrade it to {@link
 * Emotion#NEUTRAL} for backends that cannot voice it before {@link SynthesisBackend#synthesize} is
 * called.
 */
public record SynthesisRequest(String text, VoiceSpec voice, Emotion emotion) {

  /** Returns a copy of this request with a different emotion, leaving text and voice untouched. */
  public SynthesisRequest withEmotion(Emotion newEmotion) {
    if (newEmotion == emotion) {
      return this;
    }
    return new SynthesisRequest(text, voice, newEmotion);
  }
}
