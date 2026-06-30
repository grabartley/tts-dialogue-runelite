package com.grahambartley;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tolerant name matching: strips tags, normalises non-breaking spaces, trims. */
public class NameNormalizerTest {

  @Test
  public void stripsColorTagsTrimsAndNormalisesNbsp() {
    assertEquals("Hans", NameNormalizer.normalize("<col=0000ff>Hans</col>"));
    assertEquals("Hans", NameNormalizer.normalize("  Hans  "));
    assertEquals("Father Aereck", NameNormalizer.normalize("Father Aereck"));
    assertEquals("", NameNormalizer.normalize(null));
    assertEquals("", NameNormalizer.normalize("<col=ff0000></col>"));
  }

  @Test
  public void convertsNonBreakingSpaceToRegularSpace() {
    assertEquals("Big Bird", NameNormalizer.normalize("Big Bird"));
  }
}
