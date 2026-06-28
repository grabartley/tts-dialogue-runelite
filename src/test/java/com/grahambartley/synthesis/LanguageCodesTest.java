package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Language-name to BCP-47 mapping, with passthrough for codes and null for the unknown. */
public class LanguageCodesTest {

  @Test
  public void mapsCommonLanguageNamesCaseInsensitively() {
    assertEquals("fr-FR", LanguageCodes.codeFor("French"));
    assertEquals("es-ES", LanguageCodes.codeFor("spanish"));
    assertEquals("ja-JP", LanguageCodes.codeFor("  Japanese  "));
    assertEquals("zh-CN", LanguageCodes.codeFor("Mandarin"));
  }

  @Test
  public void passesThroughAnExplicitBcp47Code() {
    assertEquals("a user-typed code is accepted as-is", "pt-BR", LanguageCodes.codeFor("pt-BR"));
    assertEquals("en", LanguageCodes.codeFor("en"));
  }

  @Test
  public void returnsNullForUnknownOrBlankInput() {
    assertNull(
        "an unmapped, non-code language omits language_code", LanguageCodes.codeFor("Klingon"));
    assertNull(LanguageCodes.codeFor(""));
    assertNull(LanguageCodes.codeFor("   "));
    assertNull(LanguageCodes.codeFor(null));
  }
}
