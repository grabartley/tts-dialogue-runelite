package com.grahambartley.synthesis;

import java.util.Objects;

/**
 * One line to synthesize: the text, the resolved {@link VoiceSpec}, and the desired {@link
 * Emotion}.
 *
 * <p>This is the single unit that flows from the dialogue pipeline into a {@link SynthesisBackend}.
 * The emotion is the <em>requested</em> emotion; {@link BackendProvider} may downgrade it to {@link
 * Emotion#NEUTRAL} for backends that cannot voice it before {@link SynthesisBackend#synthesize} is
 * called.
 */
public final class SynthesisRequest {

  private final String text;
  private final VoiceSpec voice;
  private final Emotion emotion;

  public SynthesisRequest(String text, VoiceSpec voice, Emotion emotion) {
    this.text = text;
    this.voice = voice;
    this.emotion = emotion;
  }

  public String getText() {
    return text;
  }

  public VoiceSpec getVoice() {
    return voice;
  }

  public Emotion getEmotion() {
    return emotion;
  }

  /** Returns a copy of this request with a different emotion, leaving text and voice untouched. */
  public SynthesisRequest withEmotion(Emotion newEmotion) {
    if (newEmotion == emotion) {
      return this;
    }
    return new SynthesisRequest(text, voice, newEmotion);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SynthesisRequest)) {
      return false;
    }
    SynthesisRequest other = (SynthesisRequest) o;
    return Objects.equals(text, other.text)
        && Objects.equals(voice, other.voice)
        && emotion == other.emotion;
  }

  @Override
  public int hashCode() {
    return Objects.hash(text, voice, emotion);
  }
}
