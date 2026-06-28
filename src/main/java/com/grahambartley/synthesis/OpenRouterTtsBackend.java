package com.grahambartley.synthesis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.tts.Pcm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * resolves each {@link VoiceSpec} to a gender-correct Gemini voice, spread per NPC. Emotion is
 * rendered through {@link GeminiEmotionStyle}, which prepends an inline style tag to the spoken
 * {@code input} (e.g. {@code "[angry] ..."}); the backend advertises {@link
 * GeminiEmotionStyle#SUPPORTED} and {@link BackendProvider} downgrades anything outside it to
 * {@link Emotion#NEUTRAL}, which carries no tag.
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

  /**
   * Per-call ceiling so a hung cloud request cannot pin the single synthesis thread indefinitely. A
   * line that does not return within this window is abandoned (and the user falls back to the local
   * voice for that line); OSRS lines are short, so a healthy synth finishes well inside it.
   */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

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
    // Derive a call-timeout client from the injected one: newBuilder() shares the connection pool
    // and dispatcher (cheap), and never mutates the shared client's global timeouts.
    this.httpClient = httpClient.newBuilder().callTimeout(CALL_TIMEOUT).build();
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
    return EnumSet.copyOf(GeminiEmotionStyle.SUPPORTED);
  }

  @Override
  public String cacheVariant(SynthesisRequest request) {
    // Fold in everything outside (voice, emotion, original text) that changes the rendered audio,
    // so
    // a cache hit always returns the bytes the current settings would synthesize:
    //  - model: fixed today, but explicit so a future model switch can never replay another model's
    //    audio,
    //  - voice: the resolved Gemini voice,
    //  - speed: only when non-default (a short line is unaffected, so it stays a stable key),
    //  - cap: only when this specific line is long enough to actually be truncated, so changing the
    //    cap re-keys only the lines it would change rather than re-billing every short line.
    StringBuilder variant =
        new StringBuilder(MODEL).append('|').append(voiceMap.voiceFor(request.voice()));
    int speed = speedPercent();
    if (speed != 100) {
      variant.append("|s").append(speed);
    }
    int cap = config.maxCloudCharsPerLine();
    String text = request.text();
    if (cap > 0 && text != null && text.length() > cap) {
      variant.append("|c").append(cap);
    }
    // A character profile is prepended to the input as an AUDIO PROFILE block, so it changes the
    // rendered audio. Fold its content digest in (only when present) so two profiles never collide
    // and a re-tuned profile re-keys; a line with no profile keeps the pre-profile variant, so
    // existing cache entries stay valid.
    CharacterProfile profile = request.profile();
    if (profile != null) {
      variant.append("|p").append(profile.cacheKey());
    }
    return variant.toString();
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
    String cappedText = capLength(request.text(), config.maxCloudCharsPerLine());
    String styledInput = GeminiEmotionStyle.apply(cappedText, request.emotion());
    // The profile block sets the tone (accent/style/pace) and the emotion tag colours the moment;
    // they compose, so the block leads and the emotion-tagged transcript follows the divider. A
    // null
    // profile leaves the input exactly as the pre-profile backend produced it.
    CharacterProfile profile = request.profile();
    String input = profile == null ? styledInput : profile.renderPromptBlock() + styledInput;

    if (config.debugMode()) {
      String tag = GeminiEmotionStyle.tagFor(request.emotion());
      log.info(
          "[TTS voice] cloud emotion {} -> {}",
          request.emotion(),
          tag == null ? "no tag (neutral input)" : "inline tag [" + tag + "]");
      if (profile == null) {
        log.info("[TTS cloud] no character profile (plain input)");
      } else {
        log.info(
            "[TTS cloud] character profile '{}' accent='{}' (cacheKey={})",
            profile.name(),
            profile.accent(),
            profile.cacheKey());
      }
      if (cappedText.length() != request.text().length()) {
        log.info(
            "[TTS cloud] line capped {} -> {} chars (maxCloudCharsPerLine={})",
            request.text().length(),
            cappedText.length(),
            config.maxCloudCharsPerLine());
      }
    }

    JsonObject payload = new JsonObject();
    payload.addProperty("model", MODEL);
    payload.addProperty("input", input);
    payload.addProperty("voice", voice);
    payload.addProperty("response_format", RESPONSE_FORMAT);
    int speed = speedPercent();
    if (speed != 100) {
      // The model may ignore speed; sending it only when non-default keeps the default request body
      // identical to before and avoids paying for a param the model might not honour.
      payload.addProperty("speed", speed / 100.0);
      if (config.debugMode()) {
        log.info("[TTS cloud] speed {}", speed / 100.0);
      }
    }

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
      ResponseBody body = response.body();
      // Read the bytes once; on any failure they are the diagnostic payload (an OpenRouter/Gemini
      // error is usually returned as a JSON/text body, sometimes even with HTTP 200), so capturing
      // them is the only way to see why a line was rejected rather than guessing.
      byte[] bytes = body == null ? new byte[0] : body.bytes();
      String contentType = headerOrEmpty(response, "Content-Type");
      String generationId = headerOrEmpty(response, "X-Generation-Id");

      if (!response.isSuccessful()) {
        warnOnce(
            "OpenRouter TTS request failed (HTTP "
                + response.code()
                + "); check your API key. Falling back to the local voice.");
        logFailure(
            "non-2xx", response.code(), response.message(), contentType, generationId, bytes);
        return null;
      }
      if (bytes.length == 0) {
        warnOnce("OpenRouter TTS returned an empty response; falling back to the local voice.");
        logFailure(
            "empty-body", response.code(), response.message(), contentType, generationId, bytes);
        return null;
      }
      Pcm pcm = RawPcmDecoder.decode(bytes, SAMPLE_RATE);
      if (pcm == null) {
        warnOnce(
            "OpenRouter TTS returned audio that could not be decoded; falling back to the local"
                + " voice.");
        logFailure(
            "undecodable", response.code(), response.message(), contentType, generationId, bytes);
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

  /** The configured pace as a percentage of normal, clamped to the supported 50-200 range. */
  private int speedPercent() {
    int percent = config.cloudSpeedPercent();
    if (percent < 50) {
      return 50;
    }
    if (percent > 200) {
      return 200;
    }
    return percent;
  }

  /**
   * Truncates {@code text} to at most {@code maxChars} characters, cutting at the latest sentence
   * boundary in the kept window, or failing that the latest word boundary, so a capped line still
   * ends cleanly rather than mid-word. A non-positive cap or an already-short line is returned
   * unchanged. The sentence boundary is only honoured past the halfway mark so an early period does
   * not collapse a long line down to a fragment.
   */
  static String capLength(String text, int maxChars) {
    if (text == null || maxChars <= 0 || text.length() <= maxChars) {
      return text;
    }
    String window = text.substring(0, maxChars);
    for (int i = window.length() - 1; i >= maxChars / 2; i--) {
      char c = window.charAt(i);
      if (c == '.' || c == '!' || c == '?') {
        return window.substring(0, i + 1).trim();
      }
    }
    int lastSpace = window.lastIndexOf(' ');
    if (lastSpace > 0) {
      return window.substring(0, lastSpace).trim();
    }
    return window.trim();
  }

  /**
   * Logs why a cloud line was rejected: HTTP status, content type, generation id, and byte count
   * (at warn so it surfaces without debug), plus a short UTF-8 snippet of the body (at info, only
   * in debug mode) since a Gemini/OpenRouter error is typically a JSON/text body, sometimes
   * returned even with HTTP 200. This is what tells rate-limiting/quota/content errors apart from
   * genuinely bad audio.
   */
  private void logFailure(
      String kind,
      int code,
      String message,
      String contentType,
      String generationId,
      byte[] bytes) {
    log.warn(
        "[TTS cloud] {} response: HTTP {} {} contentType={} genId={} bytes={}",
        kind,
        code,
        message,
        contentType.isEmpty() ? "-" : contentType,
        generationId.isEmpty() ? "-" : generationId,
        bytes.length);
    if (config.debugMode() && bytes.length > 0) {
      log.info("[TTS cloud] {} body snippet: {}", kind, bodySnippet(bytes));
    }
  }

  private static String headerOrEmpty(Response response, String name) {
    String value = response.header(name);
    return value == null ? "" : value;
  }

  /** First chunk of a response body as printable UTF-8, for diagnosing a non-audio response. */
  private static String bodySnippet(byte[] bytes) {
    int n = Math.min(bytes.length, 300);
    String text =
        new String(bytes, 0, n, StandardCharsets.UTF_8).replaceAll("\\p{Cntrl}+", " ").trim();
    return bytes.length > n ? text + "..." : text;
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
