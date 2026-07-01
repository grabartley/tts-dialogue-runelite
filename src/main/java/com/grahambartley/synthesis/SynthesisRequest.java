package com.grahambartley.synthesis;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

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
 *
 * <p>{@code skipTranslation} forces the line to be voiced verbatim even when a non-English spoken
 * language or a global quirk is configured: the cloud backend skips the translation hop and the
 * {@code |l<language>} cache segment for such a request. It is {@code true} only for the player's
 * own public chat (voiced as typed); every dialogue line leaves it {@code false}, so the request
 * body and cache key stay byte-for-byte as before.
 *
 * <p>{@code player} marks the line as the player's own speech rather than an NPC's, so the cloud
 * backend can pick the per-speaker-class Speaking Style (Player vs NPC). It is {@code true} for
 * player dialogue, public chat, and prefetched options (all lines the player speaks) and {@code
 * false} for NPC lines. The legacy constructors default it {@code false}, so an unmarked request
 * voices as an NPC line as before.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@AllArgsConstructor
public final class SynthesisRequest {

  private final String text;
  private final VoiceSpec voice;
  private final Emotion emotion;
  private final CharacterProfile profile;
  private final boolean skipTranslation;
  private final boolean player;

  /** A request with no character profile (backward-compatible 3-arg form). */
  public SynthesisRequest(String text, VoiceSpec voice, Emotion emotion) {
    this(text, voice, emotion, null, false, false);
  }

  /** A translating request with a character profile (backward-compatible 4-arg form). */
  public SynthesisRequest(String text, VoiceSpec voice, Emotion emotion, CharacterProfile profile) {
    this(text, voice, emotion, profile, false, false);
  }

  /** A request with explicit translation behaviour but no speaker-class flag (5-arg form). */
  public SynthesisRequest(
      String text,
      VoiceSpec voice,
      Emotion emotion,
      CharacterProfile profile,
      boolean skipTranslation) {
    this(text, voice, emotion, profile, skipTranslation, false);
  }

  /**
   * Returns a copy of this request with a different emotion, leaving text, voice, profile, and the
   * translation and speaker-class behaviour intact.
   */
  public SynthesisRequest withEmotion(Emotion newEmotion) {
    if (newEmotion == emotion) {
      return this;
    }
    return new SynthesisRequest(text, voice, newEmotion, profile, skipTranslation, player);
  }
}
