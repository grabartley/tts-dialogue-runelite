package com.grahambartley.synthesis;

/**
 * A resolved character voice profile: the director's-notes a cloud TTS backend prepends to a line
 * to steer delivery. Carries the speaker's display label plus the three steering fields the Gemini
 * "AUDIO PROFILE" block exposes: {@code accent}, {@code style}, and {@code pace}.
 *
 * <p>This is the <em>resolved</em> profile, after the layered merge (default -> race -> keyword
 * category -> per-NPC override) in the profile table, so every field is populated. It is rendered
 * into the spoken {@code input} by {@link OpenRouterTtsBackend} as a leading block terminated by
 * the {@code #### TRANSCRIPT} divider; the emotion inline tag and the line text follow, so the
 * profile sets the tone and the emotion tag colours the moment (they compose).
 *
 * <p>Profiles are templates, not per-NPC-name: many NPCs resolve to the same profile, and {@link
 * #cacheKey()} is a stable digest of the four fields so two NPCs that share a profile share cached
 * audio for the same line, while editing any field re-keys only the lines it changes.
 */
public record CharacterProfile(String name, String accent, String style, String pace) {

  /** Divider Google's AUDIO PROFILE convention uses to separate direction from the spoken line. */
  static final String TRANSCRIPT_DIVIDER = "#### TRANSCRIPT";

  /**
   * Renders the leading direction block, ending with the {@code #### TRANSCRIPT} divider and a
   * newline so the caller can append the styled transcript directly. Mirrors the structured prompt
   * format Gemini steers from: an {@code AUDIO PROFILE} header and a {@code DIRECTOR'S NOTES} list
   * of Style, Accent, and Pace.
   */
  public String renderPromptBlock() {
    return "AUDIO PROFILE: "
        + name
        + "\n\nDIRECTOR'S NOTES:\n- Style: "
        + style
        + "\n- Accent: "
        + accent
        + "\n- Pace: "
        + pace
        + "\n\n"
        + TRANSCRIPT_DIVIDER
        + "\n";
  }

  /**
   * Stable, content-derived cache fragment (e.g. {@code "1f3a9c"}). Two profiles with identical
   * fields produce the same key (so NPCs sharing a profile share cached audio); changing any field
   * changes the key (so a re-tuned profile does not replay the old delivery). Folded into the cloud
   * cache variant.
   */
  public String cacheKey() {
    String joined = name + '' + accent + '' + style + '' + pace;
    return Integer.toHexString(joined.hashCode());
  }
}
