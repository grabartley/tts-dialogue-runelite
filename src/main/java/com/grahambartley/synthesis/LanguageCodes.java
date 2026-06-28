package com.grahambartley.synthesis;

import java.util.Map;

/**
 * Maps a human language name (the {@code cloudLanguage} config value, e.g. {@code "French"}) to a
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
          Map.entry("british english", "en-GB"),
          Map.entry("spanish", "es-ES"),
          Map.entry("latin american spanish", "es-419"),
          Map.entry("mexican spanish", "es-MX"),
          Map.entry("french", "fr-FR"),
          Map.entry("canadian french", "fr-CA"),
          Map.entry("german", "de-DE"),
          Map.entry("italian", "it-IT"),
          Map.entry("portuguese", "pt-PT"),
          Map.entry("european portuguese", "pt-PT"),
          Map.entry("brazilian portuguese", "pt-BR"),
          Map.entry("portuguese (brazil)", "pt-BR"),
          Map.entry("brazilian", "pt-BR"),
          Map.entry("dutch", "nl-NL"),
          Map.entry("polish", "pl-PL"),
          Map.entry("russian", "ru-RU"),
          Map.entry("ukrainian", "uk-UA"),
          Map.entry("japanese", "ja-JP"),
          Map.entry("korean", "ko-KR"),
          Map.entry("chinese", "zh-CN"),
          Map.entry("mandarin", "zh-CN"),
          Map.entry("simplified chinese", "zh-CN"),
          Map.entry("traditional chinese", "zh-TW"),
          Map.entry("cantonese", "yue-HK"),
          Map.entry("arabic", "ar-XA"),
          Map.entry("hindi", "hi-IN"),
          Map.entry("bengali", "bn-IN"),
          Map.entry("tamil", "ta-IN"),
          Map.entry("turkish", "tr-TR"),
          Map.entry("swedish", "sv-SE"),
          Map.entry("norwegian", "nb-NO"),
          Map.entry("danish", "da-DK"),
          Map.entry("finnish", "fi-FI"),
          Map.entry("icelandic", "is-IS"),
          Map.entry("greek", "el-GR"),
          Map.entry("czech", "cs-CZ"),
          Map.entry("slovak", "sk-SK"),
          Map.entry("romanian", "ro-RO"),
          Map.entry("hungarian", "hu-HU"),
          Map.entry("bulgarian", "bg-BG"),
          Map.entry("croatian", "hr-HR"),
          Map.entry("serbian", "sr-RS"),
          Map.entry("catalan", "ca-ES"),
          Map.entry("hebrew", "he-IL"),
          Map.entry("persian", "fa-IR"),
          Map.entry("farsi", "fa-IR"),
          Map.entry("vietnamese", "vi-VN"),
          Map.entry("thai", "th-TH"),
          Map.entry("indonesian", "id-ID"),
          Map.entry("malay", "ms-MY"),
          Map.entry("filipino", "fil-PH"),
          Map.entry("tagalog", "fil-PH"),
          Map.entry("welsh", "cy-GB"),
          Map.entry("irish", "ga-IE"),
          Map.entry("latin", "la"),
          Map.entry("afrikaans", "af-ZA"),
          Map.entry("swahili", "sw-KE"));

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
