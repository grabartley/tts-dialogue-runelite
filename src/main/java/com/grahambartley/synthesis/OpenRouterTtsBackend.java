package com.grahambartley.synthesis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.tts.Pcm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
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
 * than throwing, so the line is left unvoiced without crashing or blocking the game thread.
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
   * line that does not return within this window is abandoned (left unvoiced); OSRS lines are
   * short, so a healthy synth finishes well inside it.
   */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

  /** TCP/TLS handshake budget. Short: a slow connect should fail the line fast rather than hang. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * Per-read budget, generous enough for the model's prefill while still under the call timeout.
   */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

  /** Idle connections kept warm so back-to-back lines reuse a pooled connection. */
  private static final int MAX_IDLE_CONNECTIONS = 8;

  private static final Duration KEEP_ALIVE = Duration.ofMinutes(5);

  /** One speech call plus a single retry, only ever for a transient empty 200 body. */
  private static final int MAX_SPEECH_ATTEMPTS = 2;

  /** Base 429 back-off; doubled per consecutive limit hit and capped so prefetch never storms. */
  private static final long BACKOFF_BASE_MILLIS = 1_000;

  private static final long BACKOFF_MAX_MILLIS = 30_000;

  /**
   * User-facing notice shown when Cloud is selected but no API key is set. Shared with the plugin's
   * startup check so the two paths never drift.
   */
  public static final String NO_KEY_NOTICE =
      "Add an OpenRouter API key to use cloud voices, or switch Voice Backend to Local.";

  private final OkHttpClient httpClient;
  private final TTSDialogueConfig config;
  private final Gson gson;
  private final String endpoint;
  private final GeminiVoiceMap voiceMap = new GeminiVoiceMap();
  private final OpenRouterTranslator translator;

  /**
   * Per-profile digest of the stable cacheable prefix, used only in debug mode to assert the prefix
   * a given profile renders is byte-identical across the process lifetime (so Gemini's prompt cache
   * can hit). A mismatch means a non-stable field leaked into the prefix and is logged once.
   */
  private final Map<String, Integer> prefixHashes = new ConcurrentHashMap<>();

  /** Epoch-millis until which the backend is rate-limit backing off; 0 means not throttled. */
  private final AtomicLong backoffUntil = new AtomicLong();

  /** Consecutive 429s, so the back-off window grows geometrically and resets on a clean call. */
  private final AtomicInteger consecutive429 = new AtomicInteger();

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
    // Derive a long-lived keepalive client from the injected one (Hub rule: never new an
    // OkHttpClient). newBuilder() shares the dispatcher cheaply; we give it our own warm connection
    // pool so back-to-back lines skip the TCP/TLS handshake, and our own connect/read/call timeouts
    // without mutating the shared client's globals. Pinned to HTTP/1.1 deliberately: the speech
    // endpoint streams raw PCM, and HTTP/2 multiplexes concurrent calls (the prefetch pool plus the
    // live line) onto one connection, where a concurrent streamed body can come back truncated as
    // an
    // empty 200. HTTP/1.1 gives each concurrent call its own pooled connection, so they never
    // contend; sequential lines still reuse a warm connection. Built once and reused for the
    // process
    // lifetime (and the translation hop below).
    this.httpClient =
        httpClient
            .newBuilder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectionPool(
                new ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE.toMinutes(), TimeUnit.MINUTES))
            .connectTimeout(CONNECT_TIMEOUT)
            .readTimeout(READ_TIMEOUT)
            .callTimeout(CALL_TIMEOUT)
            .retryOnConnectionFailure(true)
            .build();
    this.config = config;
    this.gson = gson;
    this.endpoint = endpoint;
    // The translation hop shares the same keepalive client. Its chat-completions endpoint is the
    // sibling of the speech endpoint, so a test pointing speech at a mock server points translation
    // at the same server without extra wiring.
    String translatorEndpoint =
        endpoint.contains("/audio/speech")
            ? endpoint.replace("/audio/speech", "/chat/completions")
            : OpenRouterTranslator.PRODUCTION_ENDPOINT;
    this.translator = new OpenRouterTranslator(this.httpClient, config, gson, translatorEndpoint);
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
    int cap = config.cloudMaxChars();
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
    // A non-English target (or a global quirk) re-keys the line: the audio is the transformed
    // speech, not the source words, so the same source text must not collide across languages or
    // quirks. Plain English with no quirk adds nothing, so pre-translation cache entries stay
    // valid. A skip-translation request (public chat) is voiced verbatim, so it keeps the plain
    // pre-translation key and never collides with a translated dialogue line of the same text.
    String language = effectiveSpokenLanguage();
    if (needsTranslation(language) && !request.skipTranslation()) {
      variant.append("|l").append(language.toLowerCase());
    }
    return variant.toString();
  }

  /** A target language other than English (case-insensitive, blank treated as English). */
  static boolean needsTranslation(String language) {
    return language != null
        && !language.trim().isEmpty()
        && !language.trim().equalsIgnoreCase("English");
  }

  /**
   * The spoken language actually requested of the model: the configured language with any global
   * quirk appended, so "English" plus a Gen Z quirk becomes an "English Gen Z slang" target that
   * routes through the translation hop and is rewritten in that style. A blank language defaults to
   * English; the no-op quirk leaves the language untouched.
   */
  String effectiveSpokenLanguage() {
    return combineLanguage(config.cloudLanguage().label(), config.cloudSpeakingStyle());
  }

  /**
   * Appends a non-empty quirk phrase to the (blank-safe) base language, e.g. "French pirate speak".
   */
  static String combineLanguage(String language, TTSDialogueConfig.SpeakingStyle quirk) {
    String base = language == null || language.trim().isEmpty() ? "English" : language.trim();
    if (quirk == null || quirk.isNone()) {
      return base;
    }
    return base + " " + quirk.phrase();
  }

  @Override
  public Pcm synthesize(SynthesisRequest request) {
    if (!isAvailable()) {
      log.debug(NO_KEY_NOTICE);
      notice.accept(NO_KEY_NOTICE);
      return null;
    }
    String key = config.openRouterApiKey().trim();
    String voice = voiceMap.voiceFor(request.voice());
    String cappedText = capLength(request.text(), config.cloudMaxChars());
    // Optional first hop: a non-English target language (or a global quirk) routes the (already
    // capped) line through the translation model before it is voiced, so the spoken transcript is
    // the transformed text. A failed translation fails the line rather than voicing the wrong
    // language or caching a mistranslation under the language key. A skip-translation request
    // (public chat) is voiced exactly as typed, so it bypasses the hop even under a non-English
    // target or a global quirk.
    String language = effectiveSpokenLanguage();
    boolean translating = needsTranslation(language) && !request.skipTranslation();
    String spokenText = cappedText;
    if (translating) {
      String translated = translator.translate(cappedText, language.trim(), key);
      if (translated == null) {
        warnOnce(
            "OpenRouter translation to " + language.trim() + " failed; this line was not voiced.");
        return null;
      }
      spokenText = translated;
    }
    String styledInput = GeminiEmotionStyle.apply(spokenText, request.emotion());
    // The profile block sets the tone (accent/style/pace) and the emotion tag colours the moment;
    // they compose, so the block leads and the emotion-tagged transcript follows the divider. A
    // null
    // profile leaves the input exactly as the pre-profile backend produced it.
    CharacterProfile profile = request.profile();
    String input = profile == null ? styledInput : profile.renderPromptBlock() + styledInput;
    if (profile != null) {
      assertStablePrefix(profile);
    }

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
            "[TTS cloud] line capped {} -> {} chars (cloudMaxChars={})",
            request.text().length(),
            cappedText.length(),
            config.cloudMaxChars());
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
    // A translated line gets a BCP-47 language_code from the base language (not the quirk), so the
    // voice pronounces the text natively rather than mis-reading it with an English phoneme set.
    if (translating) {
      payload.addProperty("language_code", config.cloudLanguage().code());
    }
    // Route every call to the fastest provider (throughput sort, the :nitro equivalent). Identical
    // block on the translation hop, so routing is consistent.
    OpenRouterProvider.apply(payload);

    Request httpRequest =
        new Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer " + key)
            .addHeader("User-Agent", USER_AGENT)
            .post(
                RequestBody.create(
                    JSON_MEDIA_TYPE, gson.toJson(payload).getBytes(StandardCharsets.UTF_8)))
            .build();

    // A 200 with a zero-byte body is a transient server-side glitch (the generation id is present
    // but no audio came back), so one immediate retry recovers the line; the byte[]-backed request
    // body is reusable across calls. Any other failure is not retried here.
    for (int attempt = 1; attempt <= MAX_SPEECH_ATTEMPTS; attempt++) {
      try (Response response = httpClient.newCall(httpRequest).execute()) {
        ResponseBody body = response.body();
        // Read the bytes once; on any failure they are the diagnostic payload (an OpenRouter/Gemini
        // error is usually returned as a JSON/text body, sometimes even with HTTP 200), so
        // capturing
        // them is the only way to see why a line was rejected rather than guessing.
        byte[] bytes = body == null ? new byte[0] : body.bytes();
        String contentType = headerOrEmpty(response, "Content-Type");
        String generationId = headerOrEmpty(response, "X-Generation-Id");

        if (!response.isSuccessful()) {
          if (response.code() == 429) {
            enterBackoff();
          }
          warnOnce(
              "OpenRouter TTS request failed (HTTP "
                  + response.code()
                  + "); check your API key. This line was not voiced.");
          logFailure(
              "non-2xx", response.code(), response.message(), contentType, generationId, bytes);
          return null;
        }
        // A clean call clears any rate-limit back-off so prefetch can resume.
        clearBackoff();
        if (bytes.length == 0) {
          logFailure(
              "empty-body", response.code(), response.message(), contentType, generationId, bytes);
          if (attempt < MAX_SPEECH_ATTEMPTS) {
            log.debug("[TTS cloud] empty 200 body; retrying once");
            continue;
          }
          warnOnce("OpenRouter TTS returned an empty response; this line was not voiced.");
          return null;
        }
        Pcm pcm = RawPcmDecoder.decode(bytes, SAMPLE_RATE);
        if (pcm == null) {
          warnOnce(
              "OpenRouter TTS returned audio that could not be decoded; this line was not voiced.");
          logFailure(
              "undecodable", response.code(), response.message(), contentType, generationId, bytes);
          return null;
        }
        return pcm;
      } catch (IOException e) {
        warnOnce("OpenRouter TTS request could not reach the network; this line was not voiced.");
        log.debug("OpenRouter TTS network error: {}", e.getMessage());
        return null;
      } catch (RuntimeException e) {
        warnOnce("OpenRouter TTS request failed unexpectedly; this line was not voiced.");
        log.debug("OpenRouter TTS unexpected error: {}", e.getMessage());
        return null;
      }
    }
    return null;
  }

  @Override
  public boolean isThrottled() {
    return System.currentTimeMillis() < backoffUntil.get();
  }

  /** Opens (or widens) the rate-limit back-off window after a 429, geometrically per repeat hit. */
  private void enterBackoff() {
    long window = backoffWindowMillis(consecutive429.incrementAndGet());
    backoffUntil.set(System.currentTimeMillis() + window);
    log.debug("[TTS cloud] rate limited (429); backing off prefetch for {}ms", window);
  }

  /** Clears the back-off after any clean call so prefetch resumes immediately. */
  private void clearBackoff() {
    if (backoffUntil.get() != 0) {
      consecutive429.set(0);
      backoffUntil.set(0);
    }
  }

  /**
   * The back-off window for the n-th consecutive 429: {@code base * 2^(n-1)}, capped, so repeated
   * limits widen the pause geometrically instead of retry-storming, and a single 429 pauses only
   * briefly.
   */
  static long backoffWindowMillis(int consecutive) {
    int shift = Math.min(Math.max(consecutive, 1) - 1, 16);
    long window = BACKOFF_BASE_MILLIS << shift;
    return Math.min(window, BACKOFF_MAX_MILLIS);
  }

  /**
   * Debug-only guard: records the digest of a profile's stable cacheable prefix on first sight and
   * warns once if a later call for the same profile renders a different prefix, which would
   * silently defeat Gemini's implicit prompt cache. A no-op outside debug mode.
   */
  private void assertStablePrefix(CharacterProfile profile) {
    if (!config.debugMode()) {
      return;
    }
    int hash = profile.renderPromptBlock().hashCode();
    Integer seen = prefixHashes.putIfAbsent(profile.cacheKey(), hash);
    if (seen != null && seen != hash) {
      log.warn(
          "[TTS cloud] cacheable prefix for profile '{}' changed across calls (prompt cache will"
              + " miss); cacheKey={}",
          profile.name(),
          profile.cacheKey());
    }
  }

  /** The configured pace as a percentage of normal, clamped to the supported 50-200 range. */
  private int speedPercent() {
    int percent = config.cloudPace();
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
