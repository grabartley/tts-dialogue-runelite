package com.grahambartley;

/**
 * Normalises an NPC name for tolerant matching: strips any {@code <...>} tags, converts
 * non-breaking spaces to regular spaces, and trims. Case is left to the caller's comparison. This
 * stops cosmetic markup on the dialogue name widget from forcing a false miss against the raw
 * composition name.
 */
final class NameNormalizer {

  private NameNormalizer() {}

  static String normalize(String name) {
    if (name == null) {
      return "";
    }
    return name.replaceAll("<[^>]*>", "").replace(' ', ' ').trim();
  }
}
