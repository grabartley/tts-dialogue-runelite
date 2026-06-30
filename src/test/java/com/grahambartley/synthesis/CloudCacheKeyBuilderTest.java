package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The cloud cache-key variant string: which fragments are appended, and in what order. */
public class CloudCacheKeyBuilderTest {

  private static final CharacterProfile PROFILE =
      new CharacterProfile("Troll", "South London.", "Slow and simple.", "Heavy.");

  @Test
  public void baseKeyIsModelAndVoiceWithNoFragments() {
    assertEquals("m|v", CloudCacheKeyBuilder.build("m", "v", 100, 100, "a", 600, null, null));
  }

  @Test
  public void speedFragmentOnlyWhenNonDefault() {
    assertFalse(CloudCacheKeyBuilder.build("m", "v", 100, 100, "a", 0, null, null).contains("|s"));
    assertTrue(
        CloudCacheKeyBuilder.build("m", "v", 150, 100, "a", 0, null, null).contains("|s150"));
  }

  @Test
  public void capFragmentOnlyWhenLineWouldTruncate() {
    assertFalse(
        "a line within the cap is not re-keyed",
        CloudCacheKeyBuilder.build("m", "v", 100, 100, "ab", 3, null, null).contains("|c"));
    assertTrue(
        "a line longer than the cap folds the cap in",
        CloudCacheKeyBuilder.build("m", "v", 100, 100, "abcdef", 3, null, null).contains("|c3"));
  }

  @Test
  public void profileFragmentIsTheProfileContentKey() {
    String withProfile = CloudCacheKeyBuilder.build("m", "v", 100, 100, "a", 0, PROFILE, null);
    assertEquals("m|v|p" + PROFILE.cacheKey(), withProfile);
  }

  @Test
  public void languageFragmentWhenPresent() {
    assertTrue(
        CloudCacheKeyBuilder.build("m", "v", 100, 100, "a", 0, null, "french")
            .contains("|lfrench"));
  }

  @Test
  public void fragmentsAreAppendedInModelVoiceSpeedCapProfileLanguageOrder() {
    assertEquals(
        "m|v|s150|c3|p" + PROFILE.cacheKey() + "|lfrench",
        CloudCacheKeyBuilder.build("m", "v", 150, 100, "abcdef", 3, PROFILE, "french"));
  }
}
