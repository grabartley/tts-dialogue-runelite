package com.grahambartley.synthesis;

import java.util.Map;

/**
 * Maps a human language name (the {@code targetLanguage} config value, e.g. {@code "French"}) to a
 * BCP-47 code for the TTS {@code language_code} parameter, so a translated line is pronounced in
 * its own language rather than mis-read with an English phoneme set.
 *
 * <p>Best-effort: an unmapped language returns {@code null} and the backend simply omits {@code
 * language_code}, letting the model detect language from the translated transcript. Both the
 * English name and the bare code are accepted so a user can type either.
 */
final class LanguageCodes {

  private static final Map<String, String> CODES =
      Map.ofEntries(
          Map.entry("english", "en-US"),
          Map.entry("spanish", "es-ES"),
          Map.entry("french", "fr-FR"),
          Map.entry("german", "de-DE"),
          Map.entry("italian", "it-IT"),
          Map.entry("portuguese", "pt-PT"),
          Map.entry("dutch", "nl-NL"),
          Map.entry("polish", "pl-PL"),
          Map.entry("russian", "ru-RU"),
          Map.entry("ukrainian", "uk-UA"),
          Map.entry("japanese", "ja-JP"),
          Map.entry("korean", "ko-KR"),
          Map.entry("chinese", "zh-CN"),
          Map.entry("mandarin", "zh-CN"),
          Map.entry("arabic", "ar-XA"),
          Map.entry("hindi", "hi-IN"),
          Map.entry("turkish", "tr-TR"),
          Map.entry("swedish", "sv-SE"),
          Map.entry("norwegian", "nb-NO"),
          Map.entry("danish", "da-DK"),
          Map.entry("finnish", "fi-FI"),
          Map.entry("greek", "el-GR"),
          Map.entry("czech", "cs-CZ"),
          Map.entry("romanian", "ro-RO"),
          Map.entry("hungarian", "hu-HU"),
          Map.entry("vietnamese", "vi-VN"),
          Map.entry("thai", "th-TH"),
          Map.entry("indonesian", "id-ID"));

  private LanguageCodes() {}

  /** The BCP-47 code for a language name or code, or {@code null} when unrecognised. */
  static String codeFor(String language) {
    if (language == null) {
      return null;
    }
    String key = language.trim().toLowerCase();
    if (key.isEmpty()) {
      return null;
    }
    String mapped = CODES.get(key);
    if (mapped != null) {
      return mapped;
    }
    // Allow a user to type a BCP-47 code directly (e.g. "pt-BR"); accept anything language-tag
    // shaped and pass it through unchanged.
    return key.matches("[a-z]{2,3}(-[a-z0-9]{2,8})*") ? language.trim() : null;
  }
}
