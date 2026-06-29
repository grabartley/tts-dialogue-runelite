package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.grahambartley.TTSDialogueConfig.SpokenLanguage;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Invariants of the {@link SpokenLanguage} dropdown: the single source of truth for languages. */
public class TTSDialogueConfigTest {

  @Test
  public void englishIsTheDefaultNoTranslationLanguage() {
    assertEquals("en-US", SpokenLanguage.ENGLISH.code());
    assertTrue("English is the no-translation default", SpokenLanguage.ENGLISH.isEnglish());
    for (SpokenLanguage language : SpokenLanguage.values()) {
      assertEquals(
          "English is the only language treated as no-translation",
          language == SpokenLanguage.ENGLISH,
          language.isEnglish());
    }
  }

  @Test
  public void everyLanguageCarriesANameAndWellFormedBcp47Code() {
    for (SpokenLanguage language : SpokenLanguage.values()) {
      assertFalse(
          "a language name is fed to the prompt, so it is never blank", language.label().isEmpty());
      assertTrue(
          "the code is a BCP-47 tag: " + language.code(),
          language.code().matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*"));
    }
  }

  @Test
  public void theDropdownIsDeAliasedSoEachCodeAppearsOnce() {
    Set<String> codes = new HashSet<>();
    for (SpokenLanguage language : SpokenLanguage.values()) {
      assertTrue(
          "a duplicate code means an alias slipped in: " + language.code(),
          codes.add(language.code()));
    }
  }

  @Test
  public void toStringShowsTheNameAndCodeForDiscoverability() {
    assertEquals("English (en-US)", SpokenLanguage.ENGLISH.toString());
    assertEquals("Brazilian Portuguese (pt-BR)", SpokenLanguage.BRAZILIAN_PORTUGUESE.toString());
  }
}
