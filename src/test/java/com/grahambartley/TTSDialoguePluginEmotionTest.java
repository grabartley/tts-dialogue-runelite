package com.grahambartley;

import static org.junit.Assert.assertEquals;

import com.grahambartley.synthesis.Emotion;
import org.junit.Test;

/**
 * Covers the testable emotion-decision seam {@link TTSDialoguePlugin#resolveLineEmotion(int,
 * boolean)} that backs #26's chat-head detection. The plugin's {@code expressionEmotions} table is
 * the real bundled {@code expression-emotions.json} (loaded at construction, no client needed), so
 * these assertions exercise the same mapped ids the live widget read would feed in. The raw widget
 * read itself ({@code readHeadAnimationId}) is thin client glue and is exercised in manual QA.
 */
public class TTSDialoguePluginEmotionTest {

  /** A mapped expression id with emotion enabled resolves to the table's emotion. */
  @Test
  public void mappedIdWithEmotionEnabledResolvesToThatEmotion() {
    TTSDialoguePlugin plugin = new TTSDialoguePlugin();
    assertEquals(Emotion.ANGRY, plugin.resolveLineEmotion(614, true));
    assertEquals(Emotion.SCARED, plugin.resolveLineEmotion(596, true));
    assertEquals(Emotion.HAPPY, plugin.resolveLineEmotion(567, true));
    assertEquals(Emotion.SAD, plugin.resolveLineEmotion(610, true));
    assertEquals(Emotion.NEUTRAL, plugin.resolveLineEmotion(588, true));
  }

  /** The emotion gate forces NEUTRAL even for an id that maps to a real emotion. */
  @Test
  public void emotionDisabledForcesNeutral() {
    TTSDialoguePlugin plugin = new TTSDialoguePlugin();
    assertEquals(Emotion.NEUTRAL, plugin.resolveLineEmotion(614, false));
    assertEquals(Emotion.NEUTRAL, plugin.resolveLineEmotion(567, false));
  }

  /** {@code -1} (missing head, sprite dialogue, or one-tick race) resolves to NEUTRAL. */
  @Test
  public void noExpressionResolvesToNeutral() {
    TTSDialoguePlugin plugin = new TTSDialoguePlugin();
    assertEquals(Emotion.NEUTRAL, plugin.resolveLineEmotion(-1, true));
  }

  /** An id outside the documented table (e.g. a non-human head) resolves to NEUTRAL. */
  @Test
  public void unmappedIdResolvesToNeutral() {
    TTSDialoguePlugin plugin = new TTSDialoguePlugin();
    assertEquals(Emotion.NEUTRAL, plugin.resolveLineEmotion(123456, true));
  }
}
