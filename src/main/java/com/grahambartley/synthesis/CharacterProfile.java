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
   * Trailing whitespace is stripped from every field at construction so the rendered direction
   * block is byte-identical across calls for the same profile. That stability is what lets Gemini's
   * implicit prompt cache hit on the leading block (cheaper input, faster prefill) when the same
   * speaker speaks repeatedly; a stray trailing space leaking in from the bundled table or a config
   * field would otherwise re-key the prefix and miss the cache every line.
   */
  public CharacterProfile {
    name = stripTrailingOrNull(name);
    accent = stripTrailingOrNull(accent);
    style = stripTrailingOrNull(style);
    pace = stripTrailingOrNull(pace);
  }

  private static String stripTrailingOrNull(String field) {
    return field == null ? null : field.stripTrailing();
  }

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
