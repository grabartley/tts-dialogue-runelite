package com.grahambartley.synthesis.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import com.grahambartley.tts.Pcm;
import com.grahambartley.voice.VoiceManager.NPCGender;
import com.grahambartley.voice.VoiceManager.NPCRace;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    return clientFor(process, () -> 100);
  }

  private static ExternalEngineClient clientFor(
      FakeProcess process, java.util.function.IntSupplier pace) {
    ExternalEngineClient.ProcessFactory factory =
        new ExternalEngineClient.ProcessFactory() {
          @Override
          public Process start(List<String> command) {
            return process;
          }
        };
    return new ExternalEngineClient(Paths.get("/fake/kokoro-engine"), GSON, factory, pace);
  }

  /**
   * A health probe must never block behind an in-flight synthesize(). synthesize() holds this
   * client's monitor for the whole synth; the game thread calls isHealthy() (via isAvailable()) on
   * every dialogue dispatch and prefetch, so a synchronized health check would freeze the game
   * thread for the synth's duration. Here a synth is parked mid-response (holding the monitor) and
   * isHealthy() must still return promptly.
   */
  @Test(timeout = 10_000)
  public void isHealthyDoesNotBlockBehindAnInFlightSynthesize() throws Exception {
    CountDownLatch readBlocked = new CountDownLatch(1);
    CountDownLatch releaseRead = new CountDownLatch(1);
    AtomicBoolean announced = new AtomicBoolean();

    // stdout that blocks every read (so synthesize() parks in readResponse holding the monitor)
    // until released, then reports EOF so the synth unwinds cleanly.
    InputStream blockingStdout =
        new InputStream() {
          private int blockThenEof() throws IOException {
            if (announced.compareAndSet(false, true)) {
              readBlocked.countDown();
            }
            try {
              releaseRead.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
            return -1;
          }

          @Override
          public int read() throws IOException {
            return blockThenEof();
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            return blockThenEof();
          }
        };

    Process blockingProcess =
        new Process() {
          private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();

          @Override
          public OutputStream getOutputStream() {
            return stdin;
          }

          @Override
          public InputStream getInputStream() {
            return blockingStdout;
          }

          @Override
          public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
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
          public void destroy() {}

          @Override
          public boolean isAlive() {
            return true;
          }
        };

    ExternalEngineClient client =
        new ExternalEngineClient(
            Paths.get("/fake/kokoro-engine"), GSON, cmd -> blockingProcess, () -> 100);

    Thread synth = new Thread(() -> client.synthesize(request()), "test-synth");
    synth.setDaemon(true);
    synth.start();

    assertTrue(
        "synthesize() should reach the blocking response read (holding the monitor)",
        readBlocked.await(5, TimeUnit.SECONDS));

    AtomicBoolean healthy = new AtomicBoolean();
    Thread probe = new Thread(() -> healthy.set(client.isHealthy()), "test-probe");
    probe.setDaemon(true);
    probe.start();
    probe.join(2000);

    assertFalse(
        "isHealthy() blocked behind an in-flight synthesize() (health check is not lock-free)",
        probe.isAlive());
    assertTrue("engine process is alive, isHealthy() should report true", healthy.get());

    releaseRead.countDown();
    synth.join(2000);
  }

  @Test
  public void encodeRequestCarriesVoiceFieldsAndSpeed() {
    String line = ExternalEngineClient.encodeRequest(request(), GSON, 1.0f);
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
  public void encodeRequestEmitsTheGivenSpeed() {
    JsonObject root =
        GSON.fromJson(ExternalEngineClient.encodeRequest(request(), GSON, 1.5f), JsonObject.class);
    assertEquals(1.5f, root.get("speed").getAsFloat(), 0.0001f);
  }

  @Test
  public void clientSendsConfiguredSpeakingPaceAsSpeed() {
    // A non-default pace (150%) is read live and folded into the wire request as speed=1.5, so the
    // offline voice actually speeds up rather than always synthesizing at 1.0.
    FakeProcess process = new FakeProcess(pcmFrame(24000, new float[] {0.0f}));
    ExternalEngineClient client = clientFor(process, () -> 150);
    client.synthesize(request());
    JsonObject root = GSON.fromJson(process.capturedStdin().trim(), JsonObject.class);
    assertEquals(1.5f, root.get("speed").getAsFloat(), 0.0001f);
  }

  @Test
  public void encodeRequestOmitsSpeakerIdWhenSpecHasNone() {
    // A bare race/gender spec (no per-NPC speaker) must keep the line byte-for-byte the pre-#78
    // request so old/other engines are unaffected.
    JsonObject root =
        GSON.fromJson(ExternalEngineClient.encodeRequest(request(), GSON, 1.0f), JsonObject.class);
    assertFalse(root.has("speakerId"));
  }

  @Test
  public void encodeRequestCarriesSpeakerIdWhenSpecHasOne() {
    // Per-NPC voice variety (issue #78): a spec stamped with a speaker id sends it on the wire so
    // the engine voices that exact speaker.
    SynthesisRequest req =
        new SynthesisRequest(
            "Hello there.", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE, 17), Emotion.NEUTRAL);
    JsonObject root =
        GSON.fromJson(ExternalEngineClient.encodeRequest(req, GSON, 1.0f), JsonObject.class);
    assertTrue(root.has("speakerId"));
    assertEquals(17, root.get("speakerId").getAsInt());
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
  public void processDeathBeforeResponseYieldsNull() throws IOException {
    // Empty stdout: the engine produced no header at all (died immediately). EOF on the header read
    // must be a clean null, not an exception out of synthesize().
    FakeProcess process = new FakeProcess(new byte[0]);
    ExternalEngineClient client = clientFor(process);

    Pcm pcm = client.synthesize(request());

    assertNull(pcm);
  }
}
