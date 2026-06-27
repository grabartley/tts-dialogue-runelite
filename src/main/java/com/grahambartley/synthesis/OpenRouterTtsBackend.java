package com.grahambartley.synthesis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.tts.Pcm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Cloud synthesis through OpenRouter's OpenAI-compatible speech endpoint.
 *
 * <p>It POSTs a JSON body ({@code model}, {@code input}, {@code voice}, {@code response_format}) to
 * {@code https://openrouter.ai/api/v1/audio/speech} over the injected {@link OkHttpClient} (never a
 * fresh client), authenticating with a user-supplied {@code Bearer} key. With {@code
 * response_format: "pcm"} the response is a raw, headerless stream of signed 16-bit little-endian
 * mono samples at 24 kHz, decoded by {@link RawPcmDecoder} into {@link Pcm} at its true rate so
 * playback is not pitch-shifted.
 *
 * <p>The model is fixed to Gemini 3.1 Flash TTS, the one OpenRouter speech model with both a voice
 * catalog rich enough to map every race/gender and full emotion support. {@link GeminiVoiceMap}
 * resolves each {@link VoiceSpec} to a gender-correct Gemini voice, spread per NPC. Emotion
 * rendering is a follow-up, so the backend advertises {@link Emotion#NEUTRAL} only for now and
 * {@link BackendProvider} downgrades every line to neutral.
 *
 * <p>It is selected when {@link TTSDialogueConfig#voiceBackend()} is {@code CLOUD} and reports
 * {@link #isAvailable()} only when an API key is set. Every failure path (missing key, non-2xx,
 * network error, empty/undecodable body) returns {@code null} and surfaces a one-time notice rather
 * than throwing, so {@link BackendProvider} falls back to the local backend without crashing or
 * blocking the game thread.
 */
@Slf4j
public final class OpenRouterTtsBackend implements SynthesisBackend {

  /** Stable backend id, matched by {@link BackendProvider} when {@code CLOUD} is selected. */
  public static final String ID = "cloud-openrouter";

  /** OpenRouter {@code pcm} output is headerless 16-bit LE mono at this rate. */
  static final int SAMPLE_RATE = 24_000;

  static final String RESPONSE_FORMAT = "pcm";

  /**
   * The fixed OpenRouter speech model: the only one meeting both the voice-variety and emotion
   * bars.
   */
  static final String MODEL = "google/gemini-3.1-flash-tts-preview";

  private static final String PRODUCTION_ENDPOINT = "https://openrouter.ai/api/v1/audio/speech";
  private static final String USER_AGENT = "tts-dialogue-runelite";
  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  private final OkHttpClient httpClient;
  private final TTSDialogueConfig config;
  private final Gson gson;
  private final String endpoint;
  private final GeminiVoiceMap voiceMap = new GeminiVoiceMap();

  /** One-time user notice hook for cloud failures; defaults to a no-op. */
  private Consumer<String> notice = msg -> {};

  /** Guards the one-time notice so a sustained outage does not spam the chat box. */
  private boolean warned;

  public OpenRouterTtsBackend(OkHttpClient httpClient, TTSDialogueConfig config, Gson gson) {
    this(httpClient, config, gson, PRODUCTION_ENDPOINT);
  }

  /**
   * Test seam: lets a test point the request at a mock web server instead of the live OpenRouter
   * host while exercising the real header, JSON body, decode, and error handling.
   */
  OpenRouterTtsBackend(
      OkHttpClient httpClient, TTSDialogueConfig config, Gson gson, String endpoint) {
    this.httpClient = httpClient;
    this.config = config;
    this.gson = gson;
    this.endpoint = endpoint;
  }

  /** Registers a one-time notice hook (e.g. a chat or log message) for cloud failures. */
  public void setNotice(Consumer<String> notice) {
    this.notice = notice == null ? msg -> {} : notice;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public boolean isAvailable() {
    return isNonBlank(config.openRouterApiKey());
  }

  @Override
  public EnumSet<Emotion> supportedEmotions() {
    return EnumSet.of(Emotion.NEUTRAL);
  }

  @Override
  public String cacheVariant(SynthesisRequest request) {
    // The model is fixed, so the only backend-specific render variant is the resolved Gemini voice.
    return voiceMap.voiceFor(request.voice());
  }

  @Override
  public Pcm synthesize(SynthesisRequest request) {
    if (!isAvailable()) {
      warnOnce(
          "Add an OpenRouter API key for cloud voices; using the free local voice until then.");
      return null;
    }
    String key = config.openRouterApiKey().trim();
    String voice = voiceMap.voiceFor(request.voice());

    JsonObject payload = new JsonObject();
    payload.addProperty("model", MODEL);
    payload.addProperty("input", request.text());
    payload.addProperty("voice", voice);
    payload.addProperty("response_format", RESPONSE_FORMAT);

    Request httpRequest =
        new Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer " + key)
            .addHeader("User-Agent", USER_AGENT)
            .post(
                RequestBody.create(
                    JSON_MEDIA_TYPE, gson.toJson(payload).getBytes(StandardCharsets.UTF_8)))
            .build();

    try (Response response = httpClient.newCall(httpRequest).execute()) {
      if (!response.isSuccessful()) {
        warnOnce(
            "OpenRouter TTS request failed (HTTP "
                + response.code()
                + "); check your API key. Falling back to the local voice.");
        log.debug("OpenRouter TTS non-2xx response: {} {}", response.code(), response.message());
        return null;
      }
      ResponseBody body = response.body();
      if (body == null) {
        warnOnce("OpenRouter TTS returned an empty response; falling back to the local voice.");
        return null;
      }
      Pcm pcm = RawPcmDecoder.decode(body.bytes(), SAMPLE_RATE);
      if (pcm == null) {
        warnOnce(
            "OpenRouter TTS returned audio that could not be decoded; falling back to the local"
                + " voice.");
        log.debug("OpenRouter TTS response body was not decodable 16-bit PCM");
        return null;
      }
      return pcm;
    } catch (IOException e) {
      warnOnce(
          "OpenRouter TTS request could not reach the network; falling back to the local voice.");
      log.debug("OpenRouter TTS network error: {}", e.getMessage());
      return null;
    } catch (RuntimeException e) {
      warnOnce("OpenRouter TTS request failed unexpectedly; falling back to the local voice.");
      log.debug("OpenRouter TTS unexpected error: {}", e.getMessage());
      return null;
    }
  }

  private void warnOnce(String message) {
    log.debug(message);
    if (!warned) {
      warned = true;
      notice.accept(message);
    }
  }

  private static boolean isNonBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
