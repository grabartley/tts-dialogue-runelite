package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The rendered AUDIO PROFILE block format and the content-derived cache key. */
public class CharacterProfileTest {

  private static final CharacterProfile WIZARD =
      new CharacterProfile(
          "Wizard", "Distinguished elderly British English.", "A wise old wizard.", "Measured.");

  @Test
  public void renderPromptBlockMatchesTheGeminiDirectorsNotesFormat() {
    assertEquals(
        "VOICE ONLY THE TRANSCRIPT BELOW THE DIVIDER, WORD FOR WORD. ADD NO WORDS OF YOUR OWN.\n\n"
            + "AUDIO PROFILE: Wizard\n\n"
            + "DIRECTOR'S NOTES:\n"
            + "- Style: A wise old wizard.\n"
            + "- Accent: Distinguished elderly British English.\n"
            + "- Pace: Measured.\n\n"
            + "#### TRANSCRIPT\n",
        WIZARD.renderPromptBlock());
  }

  @Test
  public void renderPromptBlockLeadsWithTheStaticGuardLine() {
    assertTrue(
        "every block starts with the cache-stable guard instruction",
        WIZARD
            .renderPromptBlock()
            .startsWith(
                "VOICE ONLY THE TRANSCRIPT BELOW THE DIVIDER, WORD FOR WORD."
                    + " ADD NO WORDS OF YOUR OWN.\n\n"));
  }

  @Test
  public void renderPromptBlockEndsAtTheDividerSoTheTranscriptAppendsCleanly() {
    String block = WIZARD.renderPromptBlock();
    assertTrue("block ends with the divider and a newline", block.endsWith("#### TRANSCRIPT\n"));
    // The caller appends the styled transcript directly after the block.
    assertTrue(
        "appending the transcript yields a single composed input",
        (block + "[happy] Greetings.").endsWith("#### TRANSCRIPT\n[happy] Greetings."));
  }

  @Test
  public void cacheKeyIsStableForIdenticalFields() {
    CharacterProfile same =
        new CharacterProfile(
            "Wizard", "Distinguished elderly British English.", "A wise old wizard.", "Measured.");
    assertEquals("identical profiles share a cache key", WIZARD.cacheKey(), same.cacheKey());
  }

  @Test
  public void trailingWhitespaceIsStrippedSoThePrefixIsByteIdenticalAndCacheStable() {
    // A stray trailing space (from the bundled table or a config field) would otherwise re-key the
    // cacheable prefix and defeat Gemini's prompt cache; it is normalised away at construction.
    CharacterProfile padded =
        new CharacterProfile(
            "Wizard  ",
            "Distinguished elderly British English.\n",
            "A wise old wizard. \t",
            "Measured.   ");
    assertEquals(
        "the rendered block is identical to the unpadded profile",
        WIZARD.renderPromptBlock(),
        padded.renderPromptBlock());
    assertEquals(
        "padded and unpadded profiles share a cache key", WIZARD.cacheKey(), padded.cacheKey());
  }

  @Test
  public void cacheKeyChangesWhenAnyFieldChanges() {
    assertNotEquals(
        "a changed style re-keys",
        WIZARD.cacheKey(),
        new CharacterProfile(
                "Wizard",
                "Distinguished elderly British English.",
                "A foolish wizard.",
                "Measured.")
            .cacheKey());
    assertNotEquals(
        "a changed accent re-keys",
        WIZARD.cacheKey(),
        new CharacterProfile("Wizard", "Irish English.", "A wise old wizard.", "Measured.")
            .cacheKey());
    assertNotEquals(
        "a changed name re-keys",
        WIZARD.cacheKey(),
        new CharacterProfile(
                "Mage", "Distinguished elderly British English.", "A wise old wizard.", "Measured.")
            .cacheKey());
  }
}
