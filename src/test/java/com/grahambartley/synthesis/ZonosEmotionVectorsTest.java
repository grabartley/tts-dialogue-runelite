package com.grahambartley.synthesis;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The plugin-owned {@code Emotion -> float[8]} Zonos emotion-vector preset map: every emotion
 * yields an 8-dim vector, NEUTRAL is neutral-dominant, and the four expressive emotions are
 * pairwise distinct and each dominated by their expected dimension. This is the part of the Zonos
 * backend that is fully verifiable without a GPU.
 */
public class ZonosEmotionVectorsTest {

  @Test
  public void everyEmotionYieldsAnEightDimVector() {
    for (Emotion emotion : Emotion.values()) {
      float[] v = ZonosEmotionVectors.forEmotion(emotion);
      assertEquals(emotion + " must yield an 8-dim vector", 8, v.length);
    }
  }

  @Test
  public void neutralIsNeutralDominant() {
    float[] v = ZonosEmotionVectors.forEmotion(Emotion.NEUTRAL);
    assertEquals(
        "neutral dimension should dominate", ZonosEmotionVectors.NEUTRAL, dominantIndex(v));
  }

  @Test
  public void expressiveEmotionsAreDominatedByExpectedDimension() {
    assertEquals(ZonosEmotionVectors.HAPPINESS, dominantIndex(forEmotion(Emotion.HAPPY)));
    assertEquals(ZonosEmotionVectors.SADNESS, dominantIndex(forEmotion(Emotion.SAD)));
    assertEquals(ZonosEmotionVectors.ANGER, dominantIndex(forEmotion(Emotion.ANGRY)));
    assertEquals(ZonosEmotionVectors.FEAR, dominantIndex(forEmotion(Emotion.SCARED)));
  }

  @Test
  public void presetsArePairwiseDistinct() {
    Emotion[] all = Emotion.values();
    for (int i = 0; i < all.length; i++) {
      for (int j = i + 1; j < all.length; j++) {
        assertNotEquals(
            all[i] + " and " + all[j] + " must be distinct vectors",
            true,
            java.util.Arrays.equals(forEmotion(all[i]), forEmotion(all[j])));
      }
    }
  }

  @Test
  public void returnedArrayIsADefensiveCopy() {
    float[] first = ZonosEmotionVectors.forEmotion(Emotion.HAPPY);
    first[0] = 99f;
    float[] second = ZonosEmotionVectors.forEmotion(Emotion.HAPPY);
    assertFalse("mutating a returned vector must not affect the shared preset", second[0] == 99f);
  }

  @Test
  public void surpriseDisgustAndOtherAreZeroEverywhere() {
    for (Emotion emotion : Emotion.values()) {
      float[] v = forEmotion(emotion);
      assertEquals(emotion + " surprise should be 0", 0f, v[ZonosEmotionVectors.SURPRISE], 0f);
      assertEquals(emotion + " disgust should be 0", 0f, v[ZonosEmotionVectors.DISGUST], 0f);
      assertEquals(emotion + " other should be 0", 0f, v[ZonosEmotionVectors.OTHER], 0f);
    }
  }

  @Test
  public void neutralPresetIsExactlyOneHotNeutral() {
    float[] expected = new float[8];
    expected[ZonosEmotionVectors.NEUTRAL] = 1.0f;
    assertArrayEquals(expected, forEmotion(Emotion.NEUTRAL), 0f);
  }

  @Test
  public void unknownEmotionDefaultsToNeutralPreset() {
    // forEmotion(null) exercises the getOrDefault path without adding a real enum value.
    assertTrue(forEmotion(null)[ZonosEmotionVectors.NEUTRAL] > 0f);
  }

  private static float[] forEmotion(Emotion emotion) {
    return ZonosEmotionVectors.forEmotion(emotion);
  }

  /** Index of the largest component, the dominant emotion dimension. */
  private static int dominantIndex(float[] v) {
    int best = 0;
    for (int i = 1; i < v.length; i++) {
      if (v[i] > v[best]) {
        best = i;
      }
    }
    return best;
  }
}
