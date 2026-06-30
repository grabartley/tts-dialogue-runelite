package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.EnumSet;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Emotion -> Gemini inline style tag mapping and the neutral no-tag passthrough. */
@RunWith(JUnitParamsRunner.class)
public class GeminiEmotionStyleTest {

  @Test
  public void supportsEveryDetectedEmotion() {
    assertEquals(
        EnumSet.of(Emotion.NEUTRAL, Emotion.HAPPY, Emotion.SAD, Emotion.ANGRY, Emotion.SCARED),
        GeminiEmotionStyle.SUPPORTED);
  }

  private Object[] tagCases() {
    return new Object[] {
      new Object[] {Emotion.HAPPY, "happy"},
      new Object[] {Emotion.SAD, "sad"},
      new Object[] {Emotion.ANGRY, "angry"},
      new Object[] {Emotion.SCARED, "fearful"},
    };
  }

  @Test
  @Parameters(method = "tagCases")
  public void mapsEachNonNeutralEmotionToItsConservativeTag(Emotion emotion, String expected) {
    assertEquals(expected, GeminiEmotionStyle.tagFor(emotion));
  }

  private Object[] noTagEmotions() {
    return new Object[] {Emotion.NEUTRAL, null};
  }

  @Test
  @Parameters(method = "noTagEmotions")
  public void neutralAndNullHaveNoTag(Emotion emotion) {
    assertNull(GeminiEmotionStyle.tagFor(emotion));
  }

  private Object[] applyCases() {
    return new Object[] {
      new Object[] {"Get out!", Emotion.ANGRY, "[angry] Get out!"},
      new Object[] {"Help me!", Emotion.SCARED, "[fearful] Help me!"},
    };
  }

  @Test
  @Parameters(method = "applyCases")
  public void applyPrependsTheBracketedTag(String text, Emotion emotion, String expected) {
    assertEquals(expected, GeminiEmotionStyle.apply(text, emotion));
  }

  private Object[] untouchedEmotions() {
    return new Object[] {Emotion.NEUTRAL, null};
  }

  @Test
  @Parameters(method = "untouchedEmotions")
  public void applyLeavesNeutralInputUntouched(Emotion emotion) {
    String text = "Well met, traveller.";
    assertSame(
        "neutral returns the same string instance", text, GeminiEmotionStyle.apply(text, emotion));
  }
}
