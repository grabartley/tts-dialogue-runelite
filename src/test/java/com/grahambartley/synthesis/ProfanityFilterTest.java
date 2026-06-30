package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The offline masker (#149): base-word hits, leetspeak/separator evasions, Scunthorpe-style
 * false-positive safety, casing/punctuation preservation, idempotency, and the load-once latency
 * budget. Exercises the bundled {@code /profanity.txt} via the no-arg constructor.
 */
@RunWith(JUnitParamsRunner.class)
public class ProfanityFilterTest {

  private final ProfanityFilter filter = new ProfanityFilter();

  private Object[] baseWordCases() {
    return new Object[] {
      new Object[] {"Oh shit off, adventurer.", "Oh **** off, adventurer."},
      // casing of clean words is preserved
      new Object[] {"You Twat!", "You ****!"},
    };
  }

  @Test
  @Parameters(method = "baseWordCases")
  public void masksABaseWordButKeepsSurroundingTextAndCasing(String input, String expected) {
    assertEquals(expected, filter.mask(input));
  }

  private Object[] leetspeakCases() {
    return new Object[] {
      new Object[] {"sh1t", "****"},
      // leading symbol substitution
      new Object[] {"@ss", "***"},
      // trailing symbol substitution
      new Object[] {"a$$", "***"},
      new Object[] {"b1tch", "*****"},
    };
  }

  @Test
  @Parameters(method = "leetspeakCases")
  public void resolvesLeetspeakEvasions(String input, String expected) {
    assertEquals(expected, filter.mask(input));
  }

  private Object[] interiorSeparatorCases() {
    return new Object[] {
      new Object[] {"f.u.c.k", "*******"},
      new Object[] {"s-h-i-t", "*******"},
      // a separator-laced word inside a sentence
      new Object[] {"you b.itch", "you ******"},
    };
  }

  @Test
  @Parameters(method = "interiorSeparatorCases")
  public void resolvesInteriorSeparatorEvasions(String input, String expected) {
    assertEquals(expected, filter.mask(input));
  }

  private Object[] scunthorpeCases() {
    return new Object[] {
      // whole-token match spares lore substrings
      new Object[] {"Scunthorpe", "Scunthorpe"},
      new Object[] {"an assassin lurks", "an assassin lurks"},
      new Object[] {"Welcome to Sussex", "Welcome to Sussex"},
      new Object[] {"a class of mages", "a class of mages"},
    };
  }

  @Test
  @Parameters(method = "scunthorpeCases")
  public void leavesScunthorpeStyleSubstringsAlone(String input, String expected) {
    assertEquals(expected, filter.mask(input));
  }

  private Object[] cleanPassthroughCases() {
    return new Object[] {
      new Object[] {
        "Greetings, traveller! The bank is to the north.",
        "Greetings, traveller! The bank is to the north."
      },
      new Object[] {null, null},
      new Object[] {"", ""},
    };
  }

  @Test
  @Parameters(method = "cleanPassthroughCases")
  public void cleanTextPassesThroughUnchangedAndNullsSurvive(String input, String expected) {
    assertEquals(expected, filter.mask(input));
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
