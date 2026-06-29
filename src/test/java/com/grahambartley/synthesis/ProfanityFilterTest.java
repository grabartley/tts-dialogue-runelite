package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The offline masker (#149): base-word hits, leetspeak/separator evasions, Scunthorpe-style
 * false-positive safety, casing/punctuation preservation, idempotency, and the load-once latency
 * budget. Exercises the bundled {@code /profanity.txt} via the no-arg constructor.
 */
public class ProfanityFilterTest {

  private final ProfanityFilter filter = new ProfanityFilter();

  @Test
  public void masksABaseWordButKeepsSurroundingTextAndCasing() {
    assertEquals("Oh **** off, adventurer.", filter.mask("Oh shit off, adventurer."));
    assertEquals("casing of clean words is preserved", "You ****!", filter.mask("You Twat!"));
  }

  @Test
  public void resolvesLeetspeakEvasions() {
    assertEquals("****", filter.mask("sh1t"));
    assertEquals("leading symbol substitution", "***", filter.mask("@ss"));
    assertEquals("trailing symbol substitution", "***", filter.mask("a$$"));
    assertEquals("*****", filter.mask("b1tch"));
  }

  @Test
  public void resolvesInteriorSeparatorEvasions() {
    assertEquals("*******", filter.mask("f.u.c.k"));
    assertEquals("*******", filter.mask("s-h-i-t"));
    assertEquals(
        "a separator-laced word inside a sentence", "you ******", filter.mask("you b.itch"));
  }

  @Test
  public void leavesScunthorpeStyleSubstringsAlone() {
    assertEquals(
        "whole-token match spares lore substrings", "Scunthorpe", filter.mask("Scunthorpe"));
    assertEquals("an assassin lurks", filter.mask("an assassin lurks"));
    assertEquals("Welcome to Sussex", filter.mask("Welcome to Sussex"));
    assertEquals("a class of mages", filter.mask("a class of mages"));
  }

  @Test
  public void cleanTextPassesThroughUnchangedAndNullsSurvive() {
    String clean = "Greetings, traveller! The bank is to the north.";
    assertEquals(clean, filter.mask(clean));
    assertEquals(null, filter.mask(null));
    assertEquals("", filter.mask(""));
  }

  @Test
  public void maskingIsIdempotent() {
    String once = filter.mask("you absolute bastard");
    assertEquals("re-masking changes nothing", once, filter.mask(once));
  }

  @Test
  public void wordlistIsParsedOnceAndMaskingStaysUnderTheLatencyBudget() {
    String line =
        "Oh shit, you absolute twat, get your arse over here before I lose my damn mind, you cretin.";
    // Warm up the JIT and prove construction is not on the per-line path.
    for (int i = 0; i < 1_000; i++) {
      filter.mask(line);
    }
    long start = System.nanoTime();
    String masked = null;
    for (int i = 0; i < 10_000; i++) {
      masked = filter.mask(line);
    }
    long perCallNanos = (System.nanoTime() - start) / 10_000;
    assertTrue("the line was actually masked", masked.contains("****"));
    assertTrue(
        "masking a representative line stays well under a sub-millisecond budget (was "
            + perCallNanos
            + "ns)",
        perCallNanos < 1_000_000);
  }
}
