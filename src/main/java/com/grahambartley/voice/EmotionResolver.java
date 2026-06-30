package com.grahambartley.voice;

import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.ExpressionEmotionTable;

/**
 * Resolves a dialogue line's {@link Emotion} from the speaker's chat-head expression animation id
 * (#26). Returns {@link Emotion#NEUTRAL} when emotion is disabled in config or the id is {@code
 * -1}/unmapped (missing head, sprite dialogue, non-human head, or the one-tick race); otherwise the
 * bundled table's mapped emotion. Never returns {@code null} and never throws.
 */
public final class EmotionResolver {

  /**
   * The bundled chathead-expression -&gt; {@link Emotion} table (#25). Loaded once and reused for
   * every line; owns the {@code -1}/unmapped -&gt; NEUTRAL contract.
   */
  private final ExpressionEmotionTable expressionEmotions;

  public EmotionResolver() {
    this(ExpressionEmotionTable.load());
  }

  EmotionResolver(ExpressionEmotionTable expressionEmotions) {
    this.expressionEmotions = expressionEmotions;
  }

  public Emotion resolve(int headAnimationId, boolean enableEmotion) {
    if (!enableEmotion) {
      return Emotion.NEUTRAL;
    }
    return expressionEmotions.resolve(headAnimationId);
  }
}
