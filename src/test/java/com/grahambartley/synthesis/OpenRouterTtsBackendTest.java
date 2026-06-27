package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.tts.Pcm;
import java.util.EnumSet;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * HTTP path, headers, JSON body, decode, availability gating, cache variant, and graceful failure.
 */
public class OpenRouterTtsBackendTest {

  /** Config with a settable key; everything else uses interface defaults. */
  private static final class TestConfig implements TTSDialogueConfig {
    String key = "";

    @Override
    public String openRouterApiKey() {
      return key;
    }
  }

  private MockWebServer server;
  private OkHttpClient client;
  private final Gson gson = new Gson();

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new OkHttpClient();
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
  }

  private OpenRouterTtsBackend backend(TestConfig config) {
    // Point the backend at the mock server while keeping the real header/body/decode/error logic.
    return new OpenRouterTtsBackend(
        client, config, gson, server.url("/api/v1/audio/speech").toString());
  }

  private static SynthesisRequest req() {
    return new SynthesisRequest(
        "Hello & welcome", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);
  }

  @Test
  public void availabilityRequiresKey() {
    TestConfig config = new TestConfig();
    assertFalse("blank key -> unavailable", backend(config).isAvailable());

    config.key = "   ";
    assertFalse("whitespace-only key -> unavailable", backend(config).isAvailable());

    config.key = "sk-or-abc";
    assertTrue("a key set -> available", backend(config).isAvailable());
  }

  @Test
  public void emotionIsNeutralOnlyUntilPerModelRenderingLands() {
    assertEquals(EnumSet.of(Emotion.NEUTRAL), backend(new TestConfig()).supportedEmotions());
  }

  @Test
  public void successfulResponseDecodesRawPcmAt24k() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";

    short[] samples = {0, 16384, -16384, 32767};
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(samples))));

    Pcm pcm = backend(config).synthesize(req());

    assertNotNull("a 200 with raw PCM yields audio", pcm);
    assertEquals(24_000, pcm.getSampleRate());
    assertEquals(samples.length, pcm.getSamples().length);
  }

  @Test
  public void sendsBearerAuthAndJsonBody() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-secret";
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    backend(config).synthesize(req());

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertEquals("/api/v1/audio/speech", recorded.getPath());
    assertEquals("Bearer sk-or-secret", recorded.getHeader("Authorization"));
    assertTrue(
        "a JSON content type is sent",
        recorded.getHeader("Content-Type").startsWith("application/json"));
    assertNotNull("a User-Agent is sent", recorded.getHeader("User-Agent"));

    JsonObject body = new JsonParser().parse(recorded.getBody().readUtf8()).getAsJsonObject();
    assertEquals("google/gemini-3.1-flash-tts-preview", body.get("model").getAsString());
    assertEquals("Hello & welcome", body.get("input").getAsString());
    assertEquals("pcm", body.get("response_format").getAsString());
    assertEquals("Charon", body.get("voice").getAsString());
  }

  @Test
  public void voiceFieldComesFromTheGeminiVoiceMap() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    SynthesisRequest female =
        new SynthesisRequest("Hi", VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE), Emotion.NEUTRAL);
    backend(config).synthesize(female);

    JsonObject body =
        new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
    assertEquals(
        "the voice is whatever the map resolves for the spec",
        new GeminiVoiceMap().voiceFor(female.voice()),
        body.get("voice").getAsString());
  }

  @Test
  public void cacheVariantIsTheResolvedVoiceSoDifferentVoicesNeverCollide() {
    OpenRouterTtsBackend backend = backend(new TestConfig());

    SynthesisRequest humanMale =
        new SynthesisRequest("a", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);
    SynthesisRequest elfFemale =
        new SynthesisRequest("a", VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE), Emotion.NEUTRAL);

    assertEquals(
        "the variant is the resolved Gemini voice",
        new GeminiVoiceMap().voiceFor(humanMale.voice()),
        backend.cacheVariant(humanMale));
    assertFalse(
        "two specs that map to different voices never share a variant",
        backend.cacheVariant(humanMale).equals(backend.cacheVariant(elfFemale)));
  }

  @Test
  public void nonSuccessResponseReturnsNullWithOneNotice() {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized"));

    int[] notices = {0};
    OpenRouterTtsBackend backend = backend(config);
    backend.setNotice(msg -> notices[0]++);

    Pcm pcm = backend.synthesize(req());

    assertNull("a non-2xx fails the line gracefully", pcm);
    assertEquals("the failure surfaces a one-time notice", 1, notices[0]);
  }

  @Test
  public void emptyBodyReturnsNull() {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    // A 200 whose body is an odd byte count is not whole 16-bit PCM, so it fails to decode.
    server.enqueue(
        new MockResponse().setResponseCode(200).setBody(new Buffer().write(new byte[] {1, 2, 3})));

    assertNull("undecodable audio fails the line gracefully", backend(config).synthesize(req()));
  }

  @Test
  public void unavailableBackendDoesNotCallNetwork() {
    TestConfig config = new TestConfig(); // no key
    OpenRouterTtsBackend backend = backend(config);

    int[] notices = {0};
    backend.setNotice(msg -> notices[0]++);

    assertNull(backend.synthesize(req()));
    assertEquals("no HTTP request when unavailable", 0, server.getRequestCount());
    assertEquals("the missing-key notice fires once", 1, notices[0]);
  }

  @Test
  public void noticeFiresAtMostOnceAcrossRepeatedFailures() {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(new MockResponse().setResponseCode(500));
    server.enqueue(new MockResponse().setResponseCode(500));

    int[] notices = {0};
    OpenRouterTtsBackend backend = backend(config);
    backend.setNotice(msg -> notices[0]++);

    backend.synthesize(req());
    backend.synthesize(req());

    assertEquals("repeated failures warn once", 1, notices[0]);
  }
}
