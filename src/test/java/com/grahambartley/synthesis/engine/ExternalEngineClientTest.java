package com.grahambartley.synthesis.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import com.grahambartley.tts.Pcm;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

/**
 * Framing tests for {@link ExternalEngineClient} against a FAKE process stream: a hand-built
 * header+PCM frame is decoded back to {@link Pcm}, plus the {@code error}-line and truncated-frame
 * recovery paths. No real engine process is spawned (those are covered, when present, by {@link
 * ExternalEngineRealIntegrationTest}).
 */
public class ExternalEngineClientTest {

  private static final Gson GSON = new Gson();

  /** A canned process: stdout is a fixed byte stream, stdin is captured for assertions. */
  private static final class FakeProcess extends Process {
    private final InputStream stdout;
    private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
    private final InputStream stderr = new ByteArrayInputStream(new byte[0]);
    private boolean alive = true;

    FakeProcess(byte[] stdoutBytes) {
      this.stdout = new ByteArrayInputStream(stdoutBytes);
    }

    @Override
    public OutputStream getOutputStream() {
      return stdin;
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return stderr;
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      alive = false;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    String capturedStdin() {
      return stdin.toString(StandardCharsets.UTF_8);
    }
  }

  private static byte[] pcmFrame(int sampleRate, float[] samples) {
    String header =
        "{\"sampleRate\":"
            + sampleRate
            + ",\"samples\":"
            + samples.length
            + ",\"format\":\"f32le\"}\n";
    ByteBuffer buf = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float s : samples) {
      buf.putFloat(s);
    }
    byte[] head = header.getBytes(StandardCharsets.UTF_8);
    byte[] body = buf.array();
    byte[] out = new byte[head.length + body.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(body, 0, out, head.length, body.length);
    return out;
  }

  private static SynthesisRequest request() {
    return new SynthesisRequest(
        "Hello there.", VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE), Emotion.NEUTRAL);
  }

  private static ExternalEngineClient clientFor(FakeProcess process) {
    ExternalEngineClient.ProcessFactory factory =
        new ExternalEngineClient.ProcessFactory() {
          @Override
          public Process start(List<String> command) {
            return process;
          }
        };
    return new ExternalEngineClient(Paths.get("/fake/kokoro-engine"), GSON, factory);
  }

  @Test
  public void encodeRequestCarriesVoiceFieldsAndSpeed() {
    String line = ExternalEngineClient.encodeRequest(request(), GSON);
    JsonObject root = GSON.fromJson(line, JsonObject.class);
    assertEquals("Hello there.", root.get("text").getAsString());
    JsonObject voice = root.getAsJsonObject("voice");
    assertEquals(false, voice.get("player").getAsBoolean());
    assertEquals("ELF", voice.get("race").getAsString());
    assertEquals("FEMALE", voice.get("gender").getAsString());
    assertEquals("NEUTRAL", root.get("emotion").getAsString());
    assertEquals(1.0f, root.get("speed").getAsFloat(), 0.0001f);
  }

  @Test
  public void decodesHeaderAndPcmFrameWithReportedSampleRate() {
    float[] samples = {0.0f, 0.5f, -0.25f, 1.0f, -1.0f};
    FakeProcess process = new FakeProcess(pcmFrame(48000, samples));
    ExternalEngineClient client = clientFor(process);

    Pcm pcm = client.synthesize(request());

    assertNotNull("happy-path frame should decode", pcm);
    // The Pcm carries the engine-reported rate (48000 here, not assumed 24000) so playback never
    // pitch-shifts.
    assertEquals(48000, pcm.getSampleRate());
    assertEquals(samples.length, pcm.getSamples().length);
    for (int i = 0; i < samples.length; i++) {
      assertEquals(samples[i], pcm.getSamples()[i], 1e-7f);
    }
    // The request line was actually written to the engine's stdin.
    assertTrue(process.capturedStdin().contains("\"race\":\"ELF\""));
  }

  @Test
  public void errorLineYieldsNullWithoutKillingFrame() {
    byte[] errLine = "{\"error\":\"boom\"}\n".getBytes(StandardCharsets.UTF_8);
    FakeProcess process = new FakeProcess(errLine);
    ExternalEngineClient client = clientFor(process);

    Pcm pcm = client.synthesize(request());

    assertNull("an error header line must surface as a failed (null) synthesis", pcm);
  }

  @Test
  public void truncatedPcmFrameYieldsNull() {
    // Header announces 5 samples (20 bytes) but only 8 bytes of PCM follow: readFully must throw
    // EOF and the client must report failure (null) rather than hang or return garbage.
    String header = "{\"sampleRate\":24000,\"samples\":5,\"format\":\"f32le\"}\n";
    byte[] head = header.getBytes(StandardCharsets.UTF_8);
    byte[] partial = new byte[8];
    byte[] truncated = new byte[head.length + partial.length];
    System.arraycopy(head, 0, truncated, 0, head.length);
    FakeProcess process = new FakeProcess(truncated);
    ExternalEngineClient client = clientFor(process);

    Pcm pcm = client.synthesize(request());

    assertNull("a truncated PCM frame must fail cleanly", pcm);
  }

  @Test
  public void encodeRequestCarriesEmotionVectorWhenSupplied() {
    float[] vec = {0.8f, 0f, 0f, 0f, 0f, 0f, 0.2f, 0f};
    String line = ExternalEngineClient.encodeRequest(request(), vec, GSON);
    JsonObject root = GSON.fromJson(line, JsonObject.class);
    com.google.gson.JsonArray arr = root.getAsJsonArray("emotionVector");
    assertNotNull("the Zonos emotion vector must be encoded into the request line", arr);
    assertEquals(8, arr.size());
    assertEquals(0.8f, arr.get(0).getAsFloat(), 1e-6f);
    assertEquals(0.2f, arr.get(6).getAsFloat(), 1e-6f);
  }

  @Test
  public void encodeRequestOmitsEmotionVectorByDefault() {
    JsonObject root =
        GSON.fromJson(ExternalEngineClient.encodeRequest(request(), GSON), JsonObject.class);
    assertNull("no vector field for engines that take none (Kokoro)", root.get("emotionVector"));
  }

  @Test
  public void synthesizeWithVectorWritesItAndStillDecodesPcm() {
    float[] samples = {0.0f, 0.5f, -0.25f};
    FakeProcess process = new FakeProcess(pcmFrame(44100, samples));
    ExternalEngineClient client = clientFor(process);

    float[] vec = {0f, 0f, 0.8f, 0f, 0f, 0f, 0.2f, 0f};
    Pcm pcm = client.synthesize(request(), vec);

    assertNotNull("the header+PCM frame must decode after a vector request", pcm);
    assertEquals(44100, pcm.getSampleRate());
    assertEquals(samples.length, pcm.getSamples().length);
    // The vector was actually written on the wire to the engine.
    assertTrue(process.capturedStdin().contains("\"emotionVector\""));
  }

  @Test
  public void handshakeDecodesGpuReport() {
    byte[] reply =
        "{\"ok\":true,\"gpu\":true,\"detail\":\"cuda:0\"}\n".getBytes(StandardCharsets.UTF_8);
    FakeProcess process = new FakeProcess(reply);
    ExternalEngineClient client = clientFor(process);

    ExternalEngineClient.Health health = client.handshake();

    assertNotNull(health);
    assertTrue("engine reported ready", health.ok());
    assertTrue("engine reported a usable GPU", health.gpu());
    assertTrue(
        "a health op was written to the engine", process.capturedStdin().contains("\"health\""));
  }

  @Test
  public void handshakeReportsNoGpuWhenEngineSaysSo() {
    byte[] reply =
        "{\"ok\":true,\"gpu\":false,\"detail\":\"no CUDA\"}\n".getBytes(StandardCharsets.UTF_8);
    ExternalEngineClient client = clientFor(new FakeProcess(reply));

    ExternalEngineClient.Health health = client.handshake();

    assertNotNull(health);
    assertTrue(health.ok());
    assertEquals(false, health.gpu());
  }

  @Test
  public void handshakeReturnsNullWhenEngineDiesBeforeReplying() {
    ExternalEngineClient client = clientFor(new FakeProcess(new byte[0]));
    assertNull("a dead engine yields no Health rather than throwing", client.handshake());
  }

  @Test
  public void processDeathBeforeResponseYieldsNull() throws IOException {
    // Empty stdout: the engine produced no header at all (died immediately). EOF on the header read
    // must be a clean null, not an exception out of synthesize().
    FakeProcess process = new FakeProcess(new byte[0]);
    ExternalEngineClient client = clientFor(process);

    Pcm pcm = client.synthesize(request());

    assertNull(pcm);
  }
}
