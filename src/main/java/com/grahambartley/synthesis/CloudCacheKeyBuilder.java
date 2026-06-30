package com.grahambartley.synthesis;

/**
 * Builds the cache-key variant for a cloud line: everything outside the (voice, emotion, original
 * text) base key that changes the rendered audio, so a cache hit always returns the bytes the
 * current settings would synthesize. Each fragment is appended only when it actually applies, so a
 * line keeps the simplest key it can and existing cache entries stay valid when an unrelated
 * setting changes.
 *
 * <ul>
 *   <li>model + voice: the base, so a future model switch or a different voice never replays the
 *       wrong audio;
 *   <li>{@code |s}: speed, only when non-default;
 *   <li>{@code |c}: cap, only when this line is long enough to actually be truncated;
 *   <li>{@code |p}: character-profile content digest, only when a profile is present;
 *   <li>{@code |l}: target language/style, only when the line is actually translated.
 * </ul>
 */
final class CloudCacheKeyBuilder {

  private CloudCacheKeyBuilder() {}

  static String build(
      String modelId,
      String voice,
      int speedPercent,
      int defaultSpeedPercent,
      String text,
      int maxChars,
      CharacterProfile profile,
      String languageFragment) {
    StringBuilder variant = new StringBuilder(modelId).append('|').append(voice);
    if (speedPercent != defaultSpeedPercent) {
      variant.append("|s").append(speedPercent);
    }
    if (maxChars > 0 && text != null && text.length() > maxChars) {
      variant.append("|c").append(maxChars);
    }
    if (profile != null) {
      variant.append("|p").append(profile.cacheKey());
    }
    if (languageFragment != null) {
      variant.append("|l").append(languageFragment);
    }
    return variant.toString();
  }
}
