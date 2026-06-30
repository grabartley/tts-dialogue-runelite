package com.grahambartley.synthesis;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.grahambartley.VoicedDialogueConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Translates a dialogue line into the configured spoken language before it is voiced, via
 * OpenRouter's chat-completions endpoint and the Gemini Flash Lite model.
 *
 * <p>This is the optional first hop of the cloud pipeline: {@link OpenRouterTtsBackend} calls it
 * only when {@link VoicedDialogueConfig#cloudLanguage()} is not English, so the common case pays no
 * extra request. The system prompt is a fixed per-language constant (all per-line variance lives in
 * the user message) so the cacheable prefix is byte-identical across calls and the model's implicit
 * prompt cache hits; the instruction preserves proper nouns, place names, item names, and OSRS
 * terminology so lore reads correctly. Every failure path returns {@code null} so the backend fails
 * the line gracefully rather than voicing the wrong language or caching a mistranslation.
 */
@Slf4j
final class OpenRouterTranslator {

  /**
   * The lightweight model used for the translation hop: fast and cheap relative to the TTS call.
   */
  static final String MODEL = "google/gemini-3.1-flash-lite-preview";

  static final String PRODUCTION_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
  private static final String USER_AGENT = "runelite-voiced-dialogue";

  /** OpenRouter app-attribution headers, shown as the app name/URL in its usage dashboard. */
  private static final String APP_TITLE = "RuneLite Voiced Dialogue";

  private static final String APP_URL = "https://github.com/grabartley/runelite-voiced-dialogue";

  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  private final OkHttpClient httpClient;
  private final VoicedDialogueConfig config;
  private final Gson gson;
  private final String endpoint;

  OpenRouterTranslator(OkHttpClient httpClient, VoicedDialogueConfig config, Gson gson) {
    this(httpClient, config, gson, PRODUCTION_ENDPOINT);
  }

  /** Test seam: points the translation request at a mock server instead of the live host. */
  OpenRouterTranslator(
      OkHttpClient httpClient, VoicedDialogueConfig config, Gson gson, String endpoint) {
    this.httpClient = httpClient;
    this.config = config;
    this.gson = gson;
    this.endpoint = endpoint;
  }

  /**
   * Returns {@code text} translated into {@code language}, or {@code null} on any failure (missing
   * key, non-2xx, network error, empty/unparseable body). The caller treats {@code null} as a
   * failed line rather than voicing untranslated text under a target-language cache key.
   */
  String translate(String text, String language, String apiKey) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    JsonObject payload = new JsonObject();
    payload.addProperty("model", MODEL);
    JsonArray messages = new JsonArray();
    messages.add(message("system", systemPrompt(language)));
    messages.add(message("user", text));
    payload.add("messages", messages);
    OpenRouterProvider.apply(payload);

    Request httpRequest =
        new Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("HTTP-Referer", APP_URL)
            .addHeader("X-Title", APP_TITLE)
            .post(
                RequestBody.create(
                    JSON_MEDIA_TYPE, gson.toJson(payload).getBytes(StandardCharsets.UTF_8)))
            .build();

    long start = System.nanoTime();
    try (Response response = httpClient.newCall(httpRequest).execute()) {
      ResponseBody body = response.body();
      String raw = body == null ? "" : body.string();
      long elapsedMs = elapsedMs(start);
      if (!response.isSuccessful()) {
        log.warn(
            "[TTS cloud] translate fail reason=non-2xx http={} elapsedMs={} inLen={} detail={}",
            response.code(),
            elapsedMs,
            text.length(),
            response.message());
        return null;
      }
      String translated = extractContent(raw);
      if (translated == null || translated.isEmpty()) {
        log.warn(
            "[TTS cloud] translate fail reason=no-content http={} elapsedMs={} inLen={}",
            response.code(),
            elapsedMs,
            text.length());
        return null;
      }
      if (config.debugMode()) {
        log.info(
            "[TTS cloud] translate ok lang={} elapsedMs={} inLen={} outLen={} -> \"{}\"",
            language,
            elapsedMs,
            text.length(),
            translated.length(),
            translated);
      }
      return translated;
    } catch (IOException | RuntimeException e) {
      log.warn(
          "[TTS cloud] translate fail reason=error elapsedMs={} inLen={} detail={}",
          elapsedMs(start),
          text.length(),
          e.getMessage());
      return null;
    }
  }

  /** Elapsed wall-clock since {@code startNanos}, in whole milliseconds, for a latency trace. */
  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  /**
   * The fixed system prompt for a target language. The wording is deterministic in {@code language}
   * (no timestamps, names, or per-line text) so the same language always produces the
   * byte-identical prefix the model's prompt cache keys on.
   */
  static String systemPrompt(String language) {
    return "You are a translation engine for an Old School RuneScape dialogue voice plugin."
        + " Translate the user's line into "
        + language
        + ". Preserve proper nouns, character names, place names, item names, and RuneScape"
        + " terminology exactly as written. Output only the translation, with no quotes, notes,"
        + " explanations, or preamble.";
  }

  private static JsonObject message(String role, String content) {
    JsonObject message = new JsonObject();
    message.addProperty("role", role);
    message.addProperty("content", content);
    return message;
  }

  /** Pulls {@code choices[0].message.content} out of a chat-completions response, trimmed. */
  private String extractContent(String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    try {
      JsonObject parsed = gson.fromJson(raw, JsonObject.class);
      if (parsed == null || !parsed.has("choices")) {
        return null;
      }
      JsonArray choices = parsed.getAsJsonArray("choices");
      if (choices.size() == 0) {
        return null;
      }
      JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
      if (message == null || !message.has("content")) {
        return null;
      }
      return message.get("content").getAsString().trim();
    } catch (RuntimeException e) {
      log.debug("[TTS cloud] translation response parse error: {}", e.getMessage());
      return null;
    }
  }
}
