package com.grahambartley.synthesis;

import java.util.regex.Pattern;

/**
 * Neutralizes the prompt-injection surface of the three free-text player direction fields (accent /
 * persona / pace) before they reach a {@link CharacterProfile}. The cloud backend interpolates
 * these verbatim into the {@code AUDIO PROFILE} / {@code DIRECTOR'S NOTES} block that leads the
 * spoken line, so without this an attacker-typed value could embed newlines plus a forged {@code
 * #### TRANSCRIPT} divider and append an arbitrary transcript the model voices, or simply carry
 * literal profanity into the prompt.
 *
 * <p>Always on, deterministic, and byte-stable: the same input always yields the same single-line
 * output, so the sanitized field keeps Gemini's implicit prompt-cache prefix hitting. Each
 * sanitized field is collapsed to a single line, stripped of the block's structural marker tokens,
 * whitespace-collapsed, length-capped, and finally profanity-masked.
 */
public final class DirectionSanitizer {

  /**
   * Defensive per-field ceiling. RuneLite enforces no length limit on a text config value, so this
   * is the only bound on how much of these fields reaches the cloud prompt. It sits far above any
   * realistic accent/persona/pace description, so a legitimate field is never truncated; it exists
   * only to stop a hostile paste from inflating the prompt, not to constrain normal input.
   */
  static final int MAX_FIELD_LENGTH = 1000;

  /** Newlines and other control characters that could break out of the single-line field. */
  private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001F\\u007F]+");

  /**
   * The block's own framing tokens, removed case-insensitively so a user cannot forge the structure
   * inline even after newlines are flattened: {@code AUDIO PROFILE}, {@code DIRECTOR'S NOTES} (with
   * or without the apostrophe), and any {@code ####}-style {@code TRANSCRIPT} divider.
   */
  private static final Pattern MARKERS =
      Pattern.compile(
          "(?i)(#+\\s*transcript|transcript\\s*####|audio\\s*profile|director'?s\\s*notes)");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private final ProfanityFilter profanityFilter;

  public DirectionSanitizer(ProfanityFilter profanityFilter) {
    this.profanityFilter = profanityFilter;
  }

  /**
   * Sanitizes one free-text direction field. Null passes through as null (callers treat blank as
   * "inherit the default"); otherwise the result is a single trimmed line, free of structural
   * markers and profanity, capped at {@link #MAX_FIELD_LENGTH} characters.
   */
  public String sanitize(String field) {
    if (field == null) {
      return null;
    }
    String flattened = CONTROL.matcher(field).replaceAll(" ");
    String demarked = MARKERS.matcher(flattened).replaceAll(" ");
    String collapsed = WHITESPACE.matcher(demarked).replaceAll(" ").trim();
    if (collapsed.length() > MAX_FIELD_LENGTH) {
      collapsed = collapsed.substring(0, MAX_FIELD_LENGTH).trim();
    }
    return profanityFilter.mask(collapsed);
  }
}
