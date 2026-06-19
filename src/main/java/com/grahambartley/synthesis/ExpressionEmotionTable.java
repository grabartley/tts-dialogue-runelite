package com.grahambartley.synthesis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the bundled {@code expression-emotions.json} table mapping an OSRS dialogue chat-head
 * expression animation id to a canonical {@link Emotion}, and resolves ids against it.
 *
 * <p>The table is the complete documented RuneScape chathead expression enum (ids 9760-9862) mapped
 * to the nearest {@link Emotion}, covering the standard human dialogue expressions (see the Emotion
 * section of {@code README.md}); non-human head classes may emit ids outside this set. This class
 * owns the documented default contract that every consumer depends on: <strong>any unmapped
 * animation id, and {@code -1} (no/stale head animation), resolves to {@link
 * Emotion#NEUTRAL}</strong>. That makes an unseen expression, a non-human head, or the one-tick
 * race where the head animation lags the line a safe no-op rather than a crash.
 *
 * <p>Emotion detection (#26) wires its runtime {@code EmotionResolver} on top of this loader to
 * read the live widget and thread the resolved {@link Emotion} into each {@link SynthesisRequest};
 * this class keeps the table + default contract loadable and testable in isolation.
 */
@Slf4j
public class ExpressionEmotionTable {

  /** Keys beginning with this prefix are documentation (for example {@code _meta}), not ids. */
  private static final String DOC_KEY_PREFIX = "_";

  static final String TABLE_RESOURCE = "/expression-emotions.json";

  /** Immutable animationId -> Emotion table loaded once from the bundled resource. */
  private final Map<Integer, Emotion> table;

  private ExpressionEmotionTable(Map<Integer, Emotion> table) {
    this.table = table;
  }

  /**
   * Loads the table from the bundled {@code /expression-emotions.json} resource. Documentation keys
   * (those starting with {@code _}) are skipped; every remaining key must parse as an integer and
   * every value must name a valid {@link Emotion}. A missing or malformed resource yields an empty
   * table, so {@link #resolve(int)} still honours the default-to-{@code NEUTRAL} contract.
   */
  public static ExpressionEmotionTable load() {
    try (InputStream stream = ExpressionEmotionTable.class.getResourceAsStream(TABLE_RESOURCE)) {
      if (stream == null) {
        log.warn(
            "Expression-emotion table {} not found - all expressions resolve to NEUTRAL",
            TABLE_RESOURCE);
        return new ExpressionEmotionTable(Collections.emptyMap());
      }
      // The bundled Gson predates the static JsonParser.parseReader API, so use the instance
      // method.
      JsonObject root =
          new JsonParser()
              .parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject();
      return new ExpressionEmotionTable(parse(root));
    } catch (Exception e) {
      log.warn(
          "Failed to load expression-emotion table {} - all expressions resolve to NEUTRAL: {}",
          TABLE_RESOURCE,
          e.getMessage());
      return new ExpressionEmotionTable(Collections.emptyMap());
    }
  }

  /**
   * Parses the raw JSON object into an animationId -> {@link Emotion} map. Documentation keys are
   * ignored; every other key must be an integer and every value a valid {@link Emotion} name, or an
   * {@link IllegalArgumentException} is thrown so malformed seeds fail loudly under test.
   */
  static Map<Integer, Emotion> parse(JsonObject root) {
    Map<Integer, Emotion> parsed = new HashMap<>();
    for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
      String key = entry.getKey();
      if (key.startsWith(DOC_KEY_PREFIX)) {
        continue;
      }
      int animationId;
      try {
        animationId = Integer.parseInt(key);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "expression-emotions.json key is not an integer animation id: " + key, e);
      }
      String emotionName = entry.getValue().getAsString();
      Emotion emotion;
      try {
        emotion = Emotion.valueOf(emotionName);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "expression-emotions.json value for id "
                + animationId
                + " is not a valid Emotion: "
                + emotionName,
            e);
      }
      parsed.put(animationId, emotion);
    }
    return Collections.unmodifiableMap(parsed);
  }

  /**
   * Resolves a chat-head expression animation id to an {@link Emotion}. Returns the mapped emotion
   * for a known human-head id; returns {@link Emotion#NEUTRAL} for {@code -1} and for any id absent
   * from the table (unseen expression or non-human head). Never returns {@code null}.
   */
  public Emotion resolve(int animationId) {
    if (animationId < 0) {
      return Emotion.NEUTRAL;
    }
    return table.getOrDefault(animationId, Emotion.NEUTRAL);
  }

  /** Number of mapped ids in the loaded table (for logging and tests). */
  public int size() {
    return table.size();
  }
}
