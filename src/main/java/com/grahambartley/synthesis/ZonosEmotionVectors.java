package com.grahambartley.synthesis;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps each {@link Emotion} the plugin detects onto a Zonos 8-dimensional emotion-conditioning
 * vector, owned plugin-side so it stays unit-testable without a GPU.
 *
 * <p>Zonos-v0.1 conditions delivery on a fixed-order vector: {@code [happiness, sadness, anger,
 * fear, surprise, disgust, neutral, other]}. Zyphra documents that emotion conditioning is somewhat
 * entangled with pitch and overall audio quality, so these presets are deliberately conservative:
 * each emotion is dominated by a single primary dimension with a neutral floor blended in, rather
 * than a hot one-hot vector. That keeps the four expressive emotions clearly distinct from one
 * another and from neutral while avoiding the artifacting that maximally-saturated vectors can
 * introduce.
 *
 * <ul>
 *   <li>{@code NEUTRAL} is neutral-dominant (the {@code neutral} dimension).
 *   <li>{@code HAPPY} leads on {@code happiness}.
 *   <li>{@code SAD} leads on {@code sadness}.
 *   <li>{@code ANGRY} leads on {@code anger}.
 *   <li>{@code SCARED} leads on {@code fear}.
 * </ul>
 *
 * The {@code surprise}, {@code disgust}, and {@code other} dimensions are left at zero across all
 * presets: the plugin's detected {@link Emotion} set does not include them, and keeping them out
 * makes the four active presets maximally separable on their primary axis.
 */
public final class ZonosEmotionVectors {

  /** Number of dimensions in a Zonos emotion vector. */
  public static final int DIMENSIONS = 8;

  // Fixed dimension indices, in Zonos's documented order.
  static final int HAPPINESS = 0;
  static final int SADNESS = 1;
  static final int ANGER = 2;
  static final int FEAR = 3;
  static final int SURPRISE = 4;
  static final int DISGUST = 5;
  static final int NEUTRAL = 6;
  static final int OTHER = 7;

  /** Weight on the emotion's primary dimension for the four expressive presets. */
  private static final float PRIMARY = 0.80f;

  /** Neutral floor blended into every expressive preset to keep delivery natural. */
  private static final float NEUTRAL_FLOOR = 0.20f;

  private static final Map<Emotion, float[]> PRESETS = buildPresets();

  private ZonosEmotionVectors() {}

  private static Map<Emotion, float[]> buildPresets() {
    Map<Emotion, float[]> map = new EnumMap<>(Emotion.class);
    map.put(Emotion.NEUTRAL, neutral());
    map.put(Emotion.HAPPY, expressive(HAPPINESS));
    map.put(Emotion.SAD, expressive(SADNESS));
    map.put(Emotion.ANGRY, expressive(ANGER));
    map.put(Emotion.SCARED, expressive(FEAR));
    return map;
  }

  /** Neutral-dominant vector: all expressive energy on the {@code neutral} dimension. */
  private static float[] neutral() {
    float[] v = new float[DIMENSIONS];
    v[NEUTRAL] = 1.0f;
    return v;
  }

  /** A preset dominated by {@code primaryDim} with a neutral floor for natural delivery. */
  private static float[] expressive(int primaryDim) {
    float[] v = new float[DIMENSIONS];
    v[primaryDim] = PRIMARY;
    v[NEUTRAL] = NEUTRAL_FLOOR;
    return v;
  }

  /**
   * Returns the 8-dim emotion vector for an emotion. The returned array is a defensive copy so
   * callers cannot mutate the shared preset.
   */
  public static float[] forEmotion(Emotion emotion) {
    float[] preset = PRESETS.getOrDefault(emotion, PRESETS.get(Emotion.NEUTRAL));
    return preset.clone();
  }
}
