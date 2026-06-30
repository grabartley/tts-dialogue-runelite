package com.grahambartley;

import static org.junit.Assert.assertEquals;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tolerant name matching: strips tags, normalises non-breaking spaces, trims. */
@RunWith(JUnitParamsRunner.class)
public class NameNormalizerTest {

  private Object[] normalizeCases() {
    return new Object[] {
      new Object[] {"<col=0000ff>Hans</col>", "Hans"},
      new Object[] {"  Hans  ", "Hans"},
      new Object[] {"Father Aereck", "Father Aereck"},
      new Object[] {null, ""},
      new Object[] {"<col=ff0000></col>", ""},
    };
  }

  @Test
  @Parameters(method = "normalizeCases")
  public void stripsColorTagsTrimsAndNormalisesNbsp(String input, String expected) {
    assertEquals(expected, NameNormalizer.normalize(input));
  }

  @Test
  public void convertsNonBreakingSpaceToRegularSpace() {
    assertEquals("Big Bird", NameNormalizer.normalize("Big Bird"));
  }
}
