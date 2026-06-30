package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Verifies the {@link ExpressionEmotionTable} loader and the documented default contract that #26's
 * resolver and the backends depend on: a documented expression id returns its mapped {@link
 * Emotion}, while any unmapped id and {@code -1} resolve to {@link Emotion#NEUTRAL}.
 */
@RunWith(JUnitParamsRunner.class)
public class ExpressionEmotionTableTest {

  @Test
  public void loadsTheFullNonNeutralExpressionTable() {
    // Every non-neutral chat-head expression seq harvested from the cache (generic block +
    // per-NPC).
    assertEquals(
        "full non-neutral expression table is expected", 41, ExpressionEmotionTable.load().size());
  }

  private Object[] documentedIdCases() {
    return new Object[] {
      // Generic universal block: chathap=happy, chatscared/chatshock=scared, chatsad, chatang.
      new Object[] {567, Emotion.HAPPY},
      new Object[] {571, Emotion.SCARED},
      new Object[] {596, Emotion.SCARED},
      new Object[] {605, Emotion.HAPPY},
      new Object[] {610, Emotion.SAD},
      new Object[] {614, Emotion.ANGRY},
      // Per-NPC expression heads: lore_lizard_chat_happy/sad, kahlith_chat_disapproving.
      new Object[] {4843, Emotion.HAPPY},
      new Object[] {4844, Emotion.SAD},
      new Object[] {8215, Emotion.ANGRY},
      // A generic neutral expression (chatneu1) is intentionally absent and defaults to NEUTRAL.
      new Object[] {588, Emotion.NEUTRAL},
    };
  }

  @Test
  @Parameters(method = "documentedIdCases")
  public void documentedIdResolvesToItsEmotion(int id, Emotion expected) {
    assertEquals(expected, ExpressionEmotionTable.load().resolve(id));
  }

  @Test
  public void unmappedIdResolvesToNeutral() {
    ExpressionEmotionTable table = ExpressionEmotionTable.load();
    // An id outside the documented set (e.g. a non-human head expression) is the default-NEUTRAL
    // case.
    assertEquals(Emotion.NEUTRAL, table.resolve(123456));
  }

  @Test
  public void negativeOneResolvesToNeutral() {
    ExpressionEmotionTable table = ExpressionEmotionTable.load();
    // -1 means no/stale head animation (missing head, sprite dialogue, or the one-tick race).
    assertEquals(Emotion.NEUTRAL, table.resolve(-1));
  }

  private Object[] neverNullIds() {
    return new Object[] {-1, 9760, 987654};
  }

  @Test
  @Parameters(method = "neverNullIds")
  public void resolveNeverReturnsNull(int id) {
    assertNotNull(ExpressionEmotionTable.load().resolve(id));
  }

  @Test
  public void parseSkipsDocKeysAndRejectsInvalidEmotion() {
    JsonObject good =
        new JsonParser().parse("{\"_meta\":\"note\",\"9760\":\"HAPPY\"}").getAsJsonObject();
    Map<Integer, Emotion> parsed = ExpressionEmotionTable.parse(good);
    assertEquals(1, parsed.size());
    assertEquals(Emotion.HAPPY, parsed.get(9760));

    JsonObject badEmotion = new JsonParser().parse("{\"9760\":\"FURIOUS\"}").getAsJsonObject();
    try {
      ExpressionEmotionTable.parse(badEmotion);
      org.junit.Assert.fail("expected an IllegalArgumentException for an invalid Emotion value");
    } catch (IllegalArgumentException expected) {
      // contract: invalid Emotion names fail loudly.
    }

    JsonObject badKey = new JsonParser().parse("{\"nine\":\"HAPPY\"}").getAsJsonObject();
    try {
      ExpressionEmotionTable.parse(badKey);
      org.junit.Assert.fail("expected an IllegalArgumentException for a non-integer id key");
    } catch (IllegalArgumentException expected) {
      // contract: non-integer keys fail loudly.
    }
  }
}
