package com.grahambartley;

import static org.junit.Assert.assertEquals;

import com.grahambartley.synthesis.Emotion;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Covers the emotion-decision seam {@link EmotionResolver#resolve(int, boolean)} backing #26's
 * chat-head detection. The table is the real bundled {@code expression-emotions.json} (loaded at
 * construction, no client needed), so these exercise the same mapped ids the live widget read feeds
 * in. The raw widget read itself ({@link DialogueWidgetReader}) is covered separately.
 */
@RunWith(JUnitParamsRunner.class)
public class EmotionResolverTest {

  private final EmotionResolver resolver = new EmotionResolver();

  private Object[] resolveCases() {
    return new Object[] {
      // A mapped expression id with emotion enabled resolves to the table's emotion.
      new Object[] {614, true, Emotion.ANGRY},
      new Object[] {596, true, Emotion.SCARED},
      new Object[] {567, true, Emotion.HAPPY},
      new Object[] {610, true, Emotion.SAD},
      new Object[] {588, true, Emotion.NEUTRAL},
      // The emotion gate forces NEUTRAL even for an id that maps to a real emotion.
      new Object[] {614, false, Emotion.NEUTRAL},
      new Object[] {567, false, Emotion.NEUTRAL},
      // -1 (missing head, sprite dialogue, or one-tick race) resolves to NEUTRAL.
      new Object[] {-1, true, Emotion.NEUTRAL},
      // An id outside the documented table (e.g. a non-human head) resolves to NEUTRAL.
      new Object[] {123456, true, Emotion.NEUTRAL},
    };
  }

  @Test
  @Parameters(method = "resolveCases")
  public void resolvesExpressionIdToEmotion(
      int expressionId, boolean emotionEnabled, Emotion expected) {
    assertEquals(expected, resolver.resolve(expressionId, emotionEnabled));
  }
}
