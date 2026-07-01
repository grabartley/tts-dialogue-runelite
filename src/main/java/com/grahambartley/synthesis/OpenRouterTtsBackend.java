package com.grahambartley.synthesis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.grahambartley.VoicedDialogueConfig;
import com.grahambartley.tts.Pcm;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
 * <p>This is the plugin's only backend and reports {@link #isAvailable()} only when an API key is
 * set. Every failure path (missing key, non-2xx, network error, empty/undecodable body) returns
 * {@code null} and surfaces a one-time notice rather than throwing, so the line is left unvoiced
 * without crashing or blocking the game thread.
 */
@Slf4j
public final class OpenRouterTtsBackend implements SynthesisBackend {

  /** Stable backend id, folded into the synthesis cache key. */
  public static final String ID = "cloud-openrouter";

  private static final String PRODUCTION_ENDPOINT = "https://openrouter.ai/api/v1/audio/speech";
  private static final String USER_AGENT = "runelite-voiced-dialogue";

  /** OpenRouter app-attribution headers, shown as the app name/URL in its usage dashboard. */
  private static final String APP_TITLE = "RuneLite Voiced Dialogue";

  private static final String APP_URL = "https://github.com/grabartley/runelite-voiced-dialogue";

  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  /**
   * Per-call ceiling so a hung cloud request cannot pin a synthesis-pool worker indefinitely. A
   * line that does not return within this window is abandoned (left unvoiced). Sized comfortably
   * above {@link #READ_TIMEOUT} so the per-read budget is actually reachable (it caps connect plus
   * the full streamed read, not a single read), giving a slow-but-valid gemini-tts generation room
   * to land instead of being killed early (#196).
   */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

  /** TCP/TLS handshake budget. Short: a slow connect should fail the line fast rather than hang. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * Per-read budget, generous enough for the model's prefill while staying under the call timeout.
   */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

  /** Base spacing before a backed-off retry of a transient network failure; doubled per attempt. */
  private static final long NETWORK_RETRY_BASE_MILLIS = 400;

  /**
   * Upper bound of the random jitter added to the backoff so concurrent lines do not retry in
   * lockstep.
   */
  private static final long NETWORK_RETRY_JITTER_MILLIS = 250;

  /** Idle connections kept warm so back-to-back lines reuse a pooled connection. */
  private static final int MAX_IDLE_CONNECTIONS = 8;

  private static final Duration KEEP_ALIVE = Duration.ofMinutes(5);

  /**
   * One speech call plus a single retry, for a transient empty 200 body, a truncated line, or a
   * read/call timeout (the retry for the latter is spaced by a backoff, the others are immediate).
   */
  private static final int MAX_SPEECH_ATTEMPTS = 2;

  /** Speaking pace as a percentage of normal: the default (skipped on the wire) and clamp range. */
  private static final int DEFAULT_SPEED_PERCENT = 100;

  private static final int MIN_SPEED_PERCENT = 50;

  private static final int MAX_SPEED_PERCENT = 200;

  /** Max bytes of a non-audio response body echoed into a diagnostic log line. */
  private static final int BODY_SNIPPET_MAX_BYTES = 300;

  /**
   * User-facing notice shown when no API key is set. Shared with the plugin's startup check so the
   * two paths never drift.
   */
  public static final String NO_KEY_NOTICE =
      "Add your OpenRouter API key in the Voiced Dialogue settings to hear dialogue; without a key,"
          + " lines are not voiced.";

  private final OkHttpClient httpClient;
  private final VoicedDialogueConfig config;
  private final Gson gson;
  private final String endpoint;
  private final TtsModelStrategy model = new GeminiTtsModel();
  private final OpenRouterTranslator translator;

  /**
   * Per-profile digest of the stable cacheable prefix, used only in debug mode to assert the prefix
   * a given profile renders is byte-identical across the process lifetime (so Gemini's prompt cache
   * can hit). A mismatch means a non-stable field leaked into the prefix and is logged once.
   */
  private final Map<String, Integer> prefixHashes = new ConcurrentHashMap<>();

  private final RateLimitBackoff backoff = new RateLimitBackoff();

  /** Backoff budget for the network-timeout retry; overridable in tests to run in milliseconds. */
  private final long networkRetryBaseMillis;

  private final long networkRetryJitterMillis;

  /** One-time user notice hook for cloud failures; defaults to a no-op. */
  private Consumer<String> notice = msg -> {};

  /** Guards the one-time notice so a sustained outage does not spam the chat box. */
  private boolean warned;

  public OpenRouterTtsBackend(OkHttpClient httpClient, VoicedDialogueConfig config, Gson gson) {
    this(httpClient, config, gson, PRODUCTION_ENDPOINT);
  }

  /**
   * Test seam: lets a test point the request at a mock web server instead of the live OpenRouter
   * host while exercising the real header, JSON body, decode, and error handling.
   */
  OpenRouterTtsBackend(
      OkHttpClient httpClient, VoicedDialogueConfig config, Gson gson, String endpoint) {
    this(httpClient, config, gson, endpoint, RetryTuning.defaults());
  }

  /**
   * Test seam: also lets a test shrink the timeout and retry-backoff budget so the network-timeout
   * retry path runs in milliseconds instead of the multi-second production budget.
   */
  OpenRouterTtsBackend(
      OkHttpClient httpClient,
      VoicedDialogueConfig config,
      Gson gson,
      String endpoint,
      RetryTuning tuning) {
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
            .connectTimeout(tuning.connectTimeout)
            .readTimeout(tuning.readTimeout)
            .callTimeout(tuning.callTimeout)
            .retryOnConnectionFailure(true)
            .build();
    this.networkRetryBaseMillis = tuning.retryBackoffBaseMillis;
    this.networkRetryJitterMillis = tuning.retryJitterMillis;
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
    return model.supportedEmotions();
  }

  @Override
  public String cacheVariant(SynthesisRequest request) {
    // A non-English target (or a global quirk) re-keys the line: the audio is the transformed
    // speech, not the source words. A skip-translation request (public chat) is voiced verbatim, so
    // it keeps the plain pre-translation key and never collides with a translated line of the same
    // text. Plain English with no quirk folds in no language fragment, so pre-translation cache
    // entries stay valid.
    String language = effectiveSpokenLanguage(request);
    String languageFragment =
        needsTranslation(language) && !request.skipTranslation() ? language.toLowerCase() : null;
    return CloudCacheKeyBuilder.build(
        model.modelId(),
        model.voiceFor(request.voice()),
        speedPercent(),
        DEFAULT_SPEED_PERCENT,
        request.text(),
        config.cloudMaxChars(),
        request.profile(),
        languageFragment);
  }

  /** A target language other than English (case-insensitive, blank treated as English). */
  static boolean needsTranslation(String language) {
    return language != null
        && !language.trim().isEmpty()
        && !language.trim().equalsIgnoreCase("English");
  }

  /**
   * The spoken language actually requested of the model for this line: the configured language with
   * the speaker-class Speaking Style appended (Player style for the player's own lines, NPC style
   * for everything else), so "English" plus a Gen Z style becomes an "English Gen Z slang" target
   * that routes through the translation hop and is rewritten in that style. A blank language
   * defaults to English; the no-op style leaves the language untouched, so a class set to None
   * skips the hop while the other class can still be styled.
   */
  String effectiveSpokenLanguage(SynthesisRequest request) {
    VoicedDialogueConfig.SpeakingStyle style =
        request.player() ? config.cloudPlayerSpeakingStyle() : config.cloudNpcSpeakingStyle();
    return combineLanguage(config.cloudLanguage().label(), style);
  }

  /**
   * Appends a non-empty quirk phrase to the (blank-safe) base language, e.g. "French pirate speak".
   */
  static String combineLanguage(String language, VoicedDialogueConfig.SpeakingStyle quirk) {
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
    String voice = model.voiceFor(request.voice());
    String cappedText = capLength(request.text(), config.cloudMaxChars());
    // Optional first hop: a non-English target language (or a global quirk) routes the (already
    // capped) line through the translation model before it is voiced, so the spoken transcript is
    // the transformed text. A failed translation fails the line rather than voicing the wrong
    // language or caching a mistranslation under the language key. A skip-translation request
    // (public chat) is voiced exactly as typed, so it bypasses the hop even under a non-English
    // target or a global quirk.
    String language = effectiveSpokenLanguage(request);
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
    String styledInput = model.styleInput(spokenText, request.emotion());
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
    payload.addProperty("model", model.modelId());
    payload.addProperty("input", input);
    payload.addProperty("voice", voice);
    payload.addProperty("response_format", model.responseFormat());
    int speed = speedPercent();
    if (speed != DEFAULT_SPEED_PERCENT) {
      // The model may ignore speed; sending it only when non-default keeps the default request body
      // identical to before and avoids paying for a param the model might not honour.
      double speedRatio = speed / (double) DEFAULT_SPEED_PERCENT;
      payload.addProperty("speed", speedRatio);
      if (config.debugMode()) {
        log.info("[TTS cloud] speed {}", speedRatio);
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
            .addHeader("HTTP-Referer", APP_URL)
            .addHeader("X-Title", APP_TITLE)
            .post(
                RequestBody.create(
                    JSON_MEDIA_TYPE, gson.toJson(payload).getBytes(StandardCharsets.UTF_8)))
            .build();

    // A 200 with a zero-byte body is a transient server-side glitch (the generation id is present
    // but no audio came back), so one immediate retry recovers the line; the byte[]-backed request
    // body is reusable across calls. A read/call timeout (or other transient IOException) is also
    // retried, but spaced by a backoff since the cause is slowness rather than a fast glitch
    // (#196).
    // A connect-phase failure (host unreachable) and any non-2xx fail the line without a retry.
    // Every attempt is timed and numbered individually so retry effectiveness is measurable from
    // the
    // logs (#162, #196).
    int inputLen = input.length();
    for (int attempt = 1; attempt <= MAX_SPEECH_ATTEMPTS; attempt++) {
      long attemptStart = System.nanoTime();
      try (Response response = httpClient.newCall(httpRequest).execute()) {
        ResponseBody body = response.body();
        // Read the bytes once; on any failure they are the diagnostic payload (an OpenRouter/Gemini
        // error is usually returned as a JSON/text body, sometimes even with HTTP 200), so
        // capturing
        // them is the only way to see why a line was rejected rather than guessing.
        byte[] bytes = body == null ? new byte[0] : body.bytes();
        String contentType = headerOrEmpty(response, "Content-Type");
        String generationId = headerOrEmpty(response, "X-Generation-Id");
        long elapsedMs = elapsedMs(attemptStart);

        if (!response.isSuccessful()) {
          if (response.code() == 429) {
            backoff.recordRateLimited();
          }
          warnOnce(
              "OpenRouter TTS request failed (HTTP "
                  + response.code()
                  + "); check your API key. This line was not voiced.");
          logFailure(
              "non-2xx",
              attempt,
              elapsedMs,
              inputLen,
              response.code(),
              response.message(),
              contentType,
              generationId,
              bytes);
          return null;
        }
        // A clean call clears any rate-limit back-off so prefetch can resume.
        backoff.recordSuccess();
        if (bytes.length == 0) {
          logFailure(
              "empty-body",
              attempt,
              elapsedMs,
              inputLen,
              response.code(),
              response.message(),
              contentType,
              generationId,
              bytes);
          if (attempt < MAX_SPEECH_ATTEMPTS) {
            log.debug(CloudSynthTrace.retry("empty-body", attempt, MAX_SPEECH_ATTEMPTS, elapsedMs));
            continue;
          }
          warnOnce("OpenRouter TTS returned an empty response; this line was not voiced.");
          return null;
        }
        Pcm pcm = model.decodeResponse(bytes);
        if (pcm == null) {
          warnOnce(
              "OpenRouter TTS returned audio that could not be decoded; this line was not voiced.");
          logFailure(
              "undecodable",
              attempt,
              elapsedMs,
              inputLen,
              response.code(),
              response.message(),
              contentType,
              generationId,
              bytes);
          return null;
        }
        // The response is transport-complete (OkHttp throws on a truncated chunked stream, handled
        // below), but the model occasionally returns a line whose audio stops mid-utterance. A
        // complete line releases into trailing silence; one that does not is rejected so a clipped
        // clip is never cached or voiced. One retry recovers the common transient case.
        if (PcmCompleteness.isTruncated(pcm)) {
          if (attempt < MAX_SPEECH_ATTEMPTS) {
            log.debug(CloudSynthTrace.retry("truncated", attempt, MAX_SPEECH_ATTEMPTS, elapsedMs));
            continue;
          }
          warnOnce("OpenRouter TTS returned a truncated line; this line was not voiced.");
          logFailure(
              "truncated",
              attempt,
              elapsedMs,
              inputLen,
              response.code(),
              response.message(),
              contentType,
              generationId,
              bytes);
          return null;
        }
        log.debug(
            CloudSynthTrace.success(
                attempt, MAX_SPEECH_ATTEMPTS, elapsedMs, inputLen, bytes.length, generationId));
        return pcm;
      } catch (ConnectException e) {
        // The host is unreachable (connection refused / no route), almost certainly an offline
        // client. Retrying only delays the failure, so this line fails fast (#196).
        warnOnce("OpenRouter TTS request could not reach the network; this line was not voiced.");
        log.warn(
            CloudSynthTrace.failure(
                "connect",
                attempt,
                MAX_SPEECH_ATTEMPTS,
                elapsedMs(attemptStart),
                inputLen,
                0,
                "",
                "",
                0,
                e.getMessage()));
        return null;
      } catch (IOException e) {
        // A read/call timeout (InterruptedIOException / SocketTimeoutException) or a transient
        // blip:
        // a slow generation deserves a backed-off retry rather than being dropped on the first
        // failure (#196). The backoff waits on a synthesis-pool worker, never the game thread, and
        // a second worker keeps serving the next line while this one waits.
        long elapsedMs = elapsedMs(attemptStart);
        if (attempt < MAX_SPEECH_ATTEMPTS) {
          log.debug(CloudSynthTrace.retry("network", attempt, MAX_SPEECH_ATTEMPTS, elapsedMs));
          backoffBeforeNetworkRetry(attempt);
          continue;
        }
        warnOnce("OpenRouter TTS request could not reach the network; this line was not voiced.");
        log.warn(
            CloudSynthTrace.failure(
                "network",
                attempt,
                MAX_SPEECH_ATTEMPTS,
                elapsedMs,
                inputLen,
                0,
                "",
                "",
                0,
                e.getMessage()));
        return null;
      } catch (RuntimeException e) {
        warnOnce("OpenRouter TTS request failed unexpectedly; this line was not voiced.");
        log.warn(
            CloudSynthTrace.failure(
                "unexpected",
                attempt,
                MAX_SPEECH_ATTEMPTS,
                elapsedMs(attemptStart),
                inputLen,
                0,
                "",
                "",
                0,
                e.getMessage()));
        return null;
      }
    }
    return null;
  }

  /** Elapsed wall-clock since {@code startNanos}, in whole milliseconds, for a latency trace. */
  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  /**
   * Spaces a retry after a transient network failure: an exponential base ({@code base * 2^(attempt
   * - 1)}) plus a small random jitter, so a brief blip or a slow generation gets a real second
   * chance without a tight retry storm and concurrent lines do not retry in lockstep. Waits on a
   * synthesis-pool worker, never the game thread, so a second worker keeps serving the next line
   * while this one waits.
   *
   * <p>The wait is a delayed {@link CompletableFuture} joined to completion rather than a sleeping
   * pool thread: the plugin uses no blocking sleep and no thread interrupt (a Hub constraint), and
   * {@link CompletableFuture#join()} parks the worker without either.
   */
  private void backoffBeforeNetworkRetry(int attempt) {
    long base = networkRetryBaseMillis << (attempt - 1);
    long jitter =
        networkRetryJitterMillis <= 0
            ? 0
            : ThreadLocalRandom.current().nextLong(networkRetryJitterMillis + 1);
    long delayMillis = base + jitter;
    if (delayMillis <= 0) {
      return;
    }
    CompletableFuture.runAsync(
            () -> {}, CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
        .join();
  }

  @Override
  public boolean isThrottled() {
    return backoff.isThrottled();
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

  /** The configured pace as a percentage of normal, clamped to the supported range. */
  private int speedPercent() {
    int percent = config.speakingPace();
    if (percent < MIN_SPEED_PERCENT) {
      return MIN_SPEED_PERCENT;
    }
    if (percent > MAX_SPEED_PERCENT) {
      return MAX_SPEED_PERCENT;
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
   * Logs why a cloud line was rejected in the standardized {@link CloudSynthTrace} shape: reason,
   * attempt, elapsed ms, input length, HTTP status, content type, generation id, and byte count (at
   * warn so it surfaces without debug), plus a short UTF-8 snippet of the body (at info, only in
   * debug mode) since a Gemini/OpenRouter error is typically a JSON/text body, sometimes returned
   * even with HTTP 200. This is what tells rate-limiting/quota/content errors apart from genuinely
   * bad audio, and lets a slow failure be quantified rather than guessed at.
   */
  private void logFailure(
      String kind,
      int attempt,
      long elapsedMs,
      int inputLen,
      int code,
      String message,
      String contentType,
      String generationId,
      byte[] bytes) {
    log.warn(
        CloudSynthTrace.failure(
            kind,
            attempt,
            MAX_SPEECH_ATTEMPTS,
            elapsedMs,
            inputLen,
            code,
            contentType,
            generationId,
            bytes.length,
            message));
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
    int n = Math.min(bytes.length, BODY_SNIPPET_MAX_BYTES);
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

  /**
   * Timeout and retry-backoff budget. {@link #defaults()} carries the production values; tests
   * construct one with millisecond budgets so the network-timeout retry path can be exercised
   * without multi-second waits.
   */
  static final class RetryTuning {
    final Duration connectTimeout;
    final Duration readTimeout;
    final Duration callTimeout;
    final long retryBackoffBaseMillis;
    final long retryJitterMillis;

    RetryTuning(
        Duration connectTimeout,
        Duration readTimeout,
        Duration callTimeout,
        long retryBackoffBaseMillis,
        long retryJitterMillis) {
      this.connectTimeout = connectTimeout;
      this.readTimeout = readTimeout;
      this.callTimeout = callTimeout;
      this.retryBackoffBaseMillis = retryBackoffBaseMillis;
      this.retryJitterMillis = retryJitterMillis;
    }

    static RetryTuning defaults() {
      return new RetryTuning(
          CONNECT_TIMEOUT,
          READ_TIMEOUT,
          CALL_TIMEOUT,
          NETWORK_RETRY_BASE_MILLIS,
          NETWORK_RETRY_JITTER_MILLIS);
    }
  }
}
