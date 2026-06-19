package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

/** HTTP path, headers, decode, availability gating, and graceful failure for the Azure backend. */
public class AzureTtsBackendTest {

  /** Config with settable key/region; everything else uses interface defaults. */
  private static final class TestConfig implements TTSDialogueConfig {
    String key = "";
    String region = "";

    @Override
    public String azureKey() {
      return key;
    }

    @Override
    public String azureRegion() {
      return region;
    }
  }

  private MockWebServer server;
  private OkHttpClient client;

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

  private AzureTtsBackend backend(TestConfig config) {
    // Point the backend at the mock server while keeping the real header/SSML/decode/error logic.
    return new AzureTtsBackend(client, config, region -> server.url("/v1").toString());
  }

  private static SynthesisRequest req(Emotion emotion) {
    return new SynthesisRequest(
        "Hello & welcome", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), emotion);
  }

  @Test
  public void availabilityRequiresKeyAndRegion() {
    TestConfig config = new TestConfig();
    assertFalse("blank key and region -> unavailable", backend(config).isAvailable());

    config.key = "abc";
    assertFalse("blank region -> unavailable", backend(config).isAvailable());

    config.key = "";
    config.region = "eastus";
    assertFalse("blank key -> unavailable", backend(config).isAvailable());

    config.key = "abc";
    assertTrue("both set -> available", backend(config).isAvailable());

    config.key = "   ";
    assertFalse("whitespace-only key -> unavailable", backend(config).isAvailable());
  }

  @Test
  public void supportsFullEmotionSet() {
    assertEquals(
        EnumSet.of(Emotion.NEUTRAL, Emotion.HAPPY, Emotion.SAD, Emotion.ANGRY, Emotion.SCARED),
        backend(new TestConfig()).supportedEmotions());
  }

  @Test
  public void successfulResponseDecodesToPcmWithTrueRate() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "abc";
    config.region = "eastus";

    short[] samples = {0, 16384, -16384, 32767};
    byte[] wav = RiffPcmDecoderTest.wav(samples, 24_000);
    server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(wav)));

    Pcm pcm = backend(config).synthesize(req(Emotion.NEUTRAL));

    assertNotNull("a 200 with RIFF PCM yields audio", pcm);
    assertEquals(24_000, pcm.getSampleRate());
    assertEquals(samples.length, pcm.getSamples().length);
  }

  @Test
  public void sendsRequiredHeadersAndSsmlBody() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "secret-key";
    config.region = "eastus";
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RiffPcmDecoderTest.wav(new short[] {1}, 24_000))));

    backend(config).synthesize(req(Emotion.HAPPY));

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertEquals("secret-key", recorded.getHeader("Ocp-Apim-Subscription-Key"));
    assertEquals("application/ssml+xml", recorded.getHeader("Content-Type"));
    assertEquals("riff-24khz-16bit-mono-pcm", recorded.getHeader("X-Microsoft-OutputFormat"));
    assertNotNull("a User-Agent is sent", recorded.getHeader("User-Agent"));

    String body = recorded.getBody().readUtf8();
    assertTrue("body is SSML for the mapped voice", body.contains("<speak"));
    assertTrue("happy -> cheerful style in the body", body.contains("style=\"cheerful\""));
    assertTrue("text is XML-escaped in the body", body.contains("Hello &amp; welcome"));
  }

  @Test
  public void nonSuccessResponseReturnsNullWithoutThrowing() {
    TestConfig config = new TestConfig();
    config.key = "abc";
    config.region = "eastus";
    server.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized"));

    int[] notices = {0};
    AzureTtsBackend backend = backend(config);
    backend.setNotice(msg -> notices[0]++);

    Pcm pcm = backend.synthesize(req(Emotion.ANGRY));

    assertNull("a non-2xx fails the line gracefully", pcm);
    assertEquals("the failure surfaces a one-time notice", 1, notices[0]);
  }

  @Test
  public void unavailableBackendDoesNotCallNetwork() {
    TestConfig config = new TestConfig(); // no key/region
    AzureTtsBackend backend = backend(config);
    assertNull(backend.synthesize(req(Emotion.NEUTRAL)));
    assertEquals("no HTTP request when unavailable", 0, server.getRequestCount());
  }

  @Test
  public void noticeFiresAtMostOnceAcrossRepeatedFailures() {
    TestConfig config = new TestConfig();
    config.key = "abc";
    config.region = "eastus";
    server.enqueue(new MockResponse().setResponseCode(500));
    server.enqueue(new MockResponse().setResponseCode(500));

    int[] notices = {0};
    AzureTtsBackend backend = backend(config);
    backend.setNotice(msg -> notices[0]++);

    backend.synthesize(req(Emotion.NEUTRAL));
    backend.synthesize(req(Emotion.NEUTRAL));

    assertEquals("repeated failures warn once", 1, notices[0]);
  }
}
