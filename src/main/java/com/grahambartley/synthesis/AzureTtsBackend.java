package com.grahambartley.synthesis;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.tts.Pcm;
import java.io.IOException;
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
 * Cloud synthesis through the Microsoft Azure Neural TTS REST API.
 *
 * <p>This is the strongest-emotion, lowest-setup backend: it renders {@link Emotion} as an Azure
 * SSML {@code mstts:express-as} style and needs only a user-supplied subscription key and region.
 * It POSTs SSML to {@code https://<region>.tts.speech.microsoft.com/cognitiveservices/v1} over the
 * injected {@link OkHttpClient} (never a fresh client), requests a RIFF/PCM output format, and
 * decodes the response to {@link Pcm} at its true 24 kHz sample rate so playback is not
 * pitch-shifted.
 *
 * <p>It is selected when {@link TTSDialogueConfig#voiceBackend()} is {@code CLOUD} and reports
 * {@link #isAvailable()} only when both key and region are set. Every failure path (missing key,
 * non-2xx, network error, undecodable body) returns {@code null} and surfaces a one-time notice
 * rather than throwing, so {@link BackendProvider} can fall back to the local backend without
 * crashing or blocking the game thread. All HTTP runs on the pipeline executor that already calls
 * {@link #synthesize}.
 */
@Slf4j
public final class AzureTtsBackend implements SynthesisBackend {

  /** Stable backend id, matched by {@link BackendProvider} when {@code CLOUD} is selected. */
  public static final String ID = "cloud-azure";

  /**
   * RIFF container, 24 kHz, 16-bit, mono, signed PCM. {@link RiffPcmDecoder} decodes exactly this;
   * the true rate travels in {@link Pcm} so {@code AudioPlayer} never resamples.
   */
  static final String OUTPUT_FORMAT = "riff-24khz-16bit-mono-pcm";

  private static final String USER_AGENT = "tts-dialogue-runelite";

  // The body is UTF-8 encoded explicitly below; the media type carries no charset so OkHttp emits
  // exactly "Content-Type: application/ssml+xml", the value the Azure REST API documents.
  private static final MediaType SSML_MEDIA_TYPE = MediaType.parse("application/ssml+xml");

  private final OkHttpClient httpClient;
  private final TTSDialogueConfig config;
  private final AzureVoiceMap voiceMap = new AzureVoiceMap();
  private final EndpointResolver endpointResolver;

  /** One-time user notice hook for cloud failures; defaults to a no-op. */
  private Consumer<String> notice = msg -> {};

  /** Guards the one-time notice so a sustained outage does not spam the chat box. */
  private boolean warned;

  public AzureTtsBackend(OkHttpClient httpClient, TTSDialogueConfig config) {
    this(httpClient, config, AzureTtsBackend::productionEndpoint);
  }

  /**
   * Test seam: lets a test point the request at a mock web server instead of the live regional host
   * while exercising the real header, SSML, decode, and error handling.
   */
  AzureTtsBackend(OkHttpClient httpClient, TTSDialogueConfig config, EndpointResolver resolver) {
    this.httpClient = httpClient;
    this.config = config;
    this.endpointResolver = resolver;
  }

  /** Builds the live Azure regional endpoint for a region. */
  private static String productionEndpoint(String region) {
    return "https://" + region + ".tts.speech.microsoft.com/cognitiveservices/v1";
  }

  /** Resolves the request URL from the configured region. */
  interface EndpointResolver {
    String endpointFor(String region);
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
    return isNonBlank(config.azureKey()) && isNonBlank(config.azureRegion());
  }

  @Override
  public EnumSet<Emotion> supportedEmotions() {
    return EnumSet.of(Emotion.NEUTRAL, Emotion.HAPPY, Emotion.SAD, Emotion.ANGRY, Emotion.SCARED);
  }

  @Override
  public Pcm synthesize(SynthesisRequest request) {
    if (!isAvailable()) {
      warnOnce("Azure voice backend has no subscription key/region set; cannot synthesize.");
      return null;
    }
    String region = config.azureRegion().trim();
    String key = config.azureKey().trim();
    String voice = voiceMap.voiceFor(request.voice());
    String ssml = AzureSsml.build(request.text(), voice, request.emotion());

    Request httpRequest =
        new Request.Builder()
            .url(endpointResolver.endpointFor(region))
            .addHeader("Ocp-Apim-Subscription-Key", key)
            // Content-Type is set from the request body's media type (application/ssml+xml) so it
            // is
            // not added here; adding it again would emit a duplicate header.
            .addHeader("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
            .addHeader("User-Agent", USER_AGENT)
            .post(
                RequestBody.create(
                    SSML_MEDIA_TYPE, ssml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .build();

    try (Response response = httpClient.newCall(httpRequest).execute()) {
      if (!response.isSuccessful()) {
        warnOnce(
            "Azure TTS request failed (HTTP "
                + response.code()
                + "); check the subscription key and region. Falling back to the local voice.");
        log.debug("Azure TTS non-2xx response: {} {}", response.code(), response.message());
        return null;
      }
      ResponseBody body = response.body();
      if (body == null) {
        warnOnce("Azure TTS returned an empty response; falling back to the local voice.");
        return null;
      }
      Pcm pcm = RiffPcmDecoder.decode(body.bytes());
      if (pcm == null) {
        warnOnce(
            "Azure TTS returned audio that could not be decoded; falling back to the local voice.");
        log.debug("Azure TTS response body was not decodable 16-bit RIFF PCM");
        return null;
      }
      return pcm;
    } catch (IOException e) {
      warnOnce("Azure TTS request could not reach the network; falling back to the local voice.");
      log.debug("Azure TTS network error: {}", e.getMessage());
      return null;
    } catch (RuntimeException e) {
      warnOnce("Azure TTS request failed unexpectedly; falling back to the local voice.");
      log.debug("Azure TTS unexpected error: {}", e.getMessage());
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
