package com.grahambartley.synthesis.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import com.grahambartley.tts.Pcm;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Plugin-side transport to the external {@code --stdio} synthesis engine (issue #36 ships the
 * engine itself).
 *
 * <p>It launches {@code <launcher> --stdio} as a child process and keeps it alive across requests.
 * Per request it writes one JSON line on stdin
 *
 * <pre>{"text","voice":{race,gender,player},"emotion","speed"}</pre>
 *
 * and reads back one JSON header line {@code {"sampleRate":N,"samples":M,"format":"f32le"}}
 * followed by exactly {@code M*4} little-endian float32 bytes, decoding them into a {@link Pcm}
 * that carries the engine-reported sample rate so {@link com.grahambartley.tts.AudioPlayer#stream}
 * never pitch-shifts. An {@code {"error":...}} header line is surfaced as a failed request without
 * killing the process.
 *
 * <p>This client is <em>thread-confined</em>: it is driven only from the single dialogue pipeline
 * thread, so there is never more than one in-flight request and stdin/stdout framing stays
 * unambiguous. The child is started lazily and restarted transparently if it has died (crash or
 * exit) before a request, so a one-off engine crash self-heals on the next line.
 */
@Slf4j
public final class ExternalEngineClient {

  private static final String STDIO_FLAG = "--stdio";
  private static final String FORMAT_F32LE = "f32le";

  private final Path launcher;
  private final Gson gson;
  private final ProcessFactory processFactory;

  private Process process;
  private OutputStream toEngine;
  private DataInputStream fromEngine;
  private BufferedReader fromEngineErr;

  /** Seam so tests can supply a fake process instead of really spawning the engine binary. */
  public interface ProcessFactory {
    Process start(List<String> command) throws IOException;
  }

  /**
   * @param launcher absolute path to the engine launcher resolved by {@link EngineInstaller}
   * @param gson the injected Gson (Hub rule: never {@code new Gson()} in plugin code)
   */
  public ExternalEngineClient(Path launcher, Gson gson) {
    this(launcher, gson, ExternalEngineClient::spawn);
  }

  ExternalEngineClient(Path launcher, Gson gson, ProcessFactory processFactory) {
    this.launcher = launcher;
    this.gson = gson;
    this.processFactory = processFactory;
  }

  private static Process spawn(List<String> command) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    // Keep stderr separate: stdout is the binary PCM channel and must stay clean.
    builder.redirectErrorStream(false);
    return builder.start();
  }

  /**
   * Starts the child process if it is not already running. Safe to call repeatedly; a no-op when
   * the engine is alive. Call before the first {@link #synthesize}.
   */
  public synchronized void start() throws IOException {
    if (isAlive()) {
      return;
    }
    stopQuietly();
    Process p = processFactory.start(List.of(launcher.toString(), STDIO_FLAG));
    this.process = p;
    this.toEngine = p.getOutputStream();
    this.fromEngine = new DataInputStream(p.getInputStream());
    this.fromEngineErr =
        new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
    log.debug("External engine started: {}", launcher);
  }

  /** Whether the child process is currently running. */
  public synchronized boolean isAlive() {
    return process != null && process.isAlive();
  }

  /**
   * Lightweight health check: the process is alive (it was started and has not exited). Heavy
   * synthesis health is proven by the first {@link #synthesize} call.
   */
  public synchronized boolean isHealthy() {
    return isAlive();
  }

  /**
   * Engine self-report from the {@code health} handshake: whether the engine is ready and whether
   * it found a usable accelerator. The Kokoro engine ignores {@code gpu} (CPU-only); the Zonos
   * backend gates its availability on it because no reliable JVM-side CUDA probe exists, so the
   * engine, which has already loaded its runtime, is the right place to detect the GPU and report
   * it back.
   */
  public static final class Health {
    private final boolean ok;
    private final boolean gpu;
    private final String detail;

    public Health(boolean ok, boolean gpu, String detail) {
      this.ok = ok;
      this.gpu = gpu;
      this.detail = detail;
    }

    public boolean ok() {
      return ok;
    }

    public boolean gpu() {
      return gpu;
    }

    public String detail() {
      return detail;
    }
  }

  /**
   * Sends a {@code {"op":"health"}} line and reads one JSON line {@code
   * {"ok":true,"gpu":true,"detail":"..."}}. Starts the engine first if needed. Returns {@code null}
   * on any transport failure (process death, malformed reply) so callers treat an
   * unreachable/unhealthy engine as "not a usable GPU engine" rather than crashing. Must be called
   * from the single pipeline thread.
   *
   * <p>This rides the same stdin/stdout pipe as synthesis; the engine answers a health line with a
   * single JSON line and no PCM frame, so it never collides with the synthesis framing.
   */
  public synchronized Health handshake() {
    try {
      start();
    } catch (IOException e) {
      log.warn("Could not start external engine for health handshake: {}", e.getMessage());
      return null;
    }
    try {
      JsonObject op = new JsonObject();
      op.addProperty("op", "health");
      String line = gson.toJson(op);
      toEngine.write(line.getBytes(StandardCharsets.UTF_8));
      toEngine.write('\n');
      toEngine.flush();

      String reply = readHeaderLine();
      if (reply == null) {
        throw new IOException("engine closed stdout before answering health handshake");
      }
      JsonObject obj = gson.fromJson(reply, JsonObject.class);
      if (obj == null) {
        throw new IOException("empty health reply");
      }
      boolean ok = obj.has("ok") && !obj.get("ok").isJsonNull() && obj.get("ok").getAsBoolean();
      boolean gpu = obj.has("gpu") && !obj.get("gpu").isJsonNull() && obj.get("gpu").getAsBoolean();
      String detail =
          obj.has("detail") && !obj.get("detail").isJsonNull()
              ? obj.get("detail").getAsString()
              : "";
      return new Health(ok, gpu, detail);
    } catch (IOException | RuntimeException e) {
      log.warn("External engine health handshake failed ({}); tearing down", e.getMessage());
      drainStderr();
      stopQuietly();
      return null;
    }
  }

  /**
   * Synthesizes one request through the engine, returning the decoded {@link Pcm} or {@code null}
   * on failure (engine error line, process death, malformed frame). Restarts the engine first if it
   * has died since the last call. Must be called from the single pipeline thread.
   */
  public synchronized Pcm synthesize(SynthesisRequest request) {
    return synthesize(request, null);
  }

  /**
   * Synthesizes one request, optionally carrying a backend-specific {@code emotionVector} the
   * engine consumes (Zonos's 8-dim emotion conditioning). The vector is the only addition over the
   * base {@code --stdio} request; everything else (text/voice/emotion/speed framing, header+PCM
   * decode, restart-on-death) is the shared transport. Pass {@code null} for engines that take no
   * vector (Kokoro). Returns the decoded {@link Pcm} or {@code null} on failure. Must be called
   * from the single pipeline thread.
   */
  public synchronized Pcm synthesize(SynthesisRequest request, float[] emotionVector) {
    return synthesize(request, emotionVector, null);
  }

  /**
   * Synthesizes one request, optionally carrying both the {@code emotionVector} (Zonos's 8-dim
   * emotion conditioning) and a {@code playerReferenceClip} path the Zonos engine clones the player
   * voice from instead of the bundled reference (issue #50). Both are optional: pass {@code null}
   * for either and that field is absent from the wire line. {@code playerReferenceClip} is only
   * ever set by the Zonos backend for player-voice lines, so Kokoro/Azure framing is unchanged.
   * Returns the decoded {@link Pcm} or {@code null} on failure. Must be called from the single
   * pipeline thread.
   */
  public synchronized Pcm synthesize(
      SynthesisRequest request, float[] emotionVector, String playerReferenceClip) {
    if (request == null || request.text() == null || request.text().isEmpty()) {
      return null;
    }
    try {
      start();
    } catch (IOException e) {
      log.warn("Could not start external engine: {}", e.getMessage());
      return null;
    }
    try {
      writeRequest(request, emotionVector, playerReferenceClip);
      return readResponse();
    } catch (IOException e) {
      log.warn("External engine request failed ({}); tearing down for restart", e.getMessage());
      drainStderr();
      stopQuietly();
      return null;
    }
  }

  /** Encodes the request as the protocol JSON line and writes it to the engine's stdin. */
  private void writeRequest(
      SynthesisRequest request, float[] emotionVector, String playerReferenceClip)
      throws IOException {
    String line = encodeRequest(request, emotionVector, playerReferenceClip, gson);
    toEngine.write(line.getBytes(StandardCharsets.UTF_8));
    toEngine.write('\n');
    toEngine.flush();
  }

  /** Builds the one-line JSON request the engine's {@code StdioProtocol} decodes. */
  static String encodeRequest(SynthesisRequest request, Gson gson) {
    return encodeRequest(request, null, null, gson);
  }

  /**
   * Builds the one-line JSON request, optionally including the {@code emotionVector} the Zonos
   * engine reads for its 8-dim emotion conditioning. When {@code emotionVector} is {@code null} the
   * line is byte-for-byte the base request, so Kokoro/Azure framing is unchanged.
   */
  static String encodeRequest(SynthesisRequest request, float[] emotionVector, Gson gson) {
    return encodeRequest(request, emotionVector, null, gson);
  }

  /**
   * Builds the one-line JSON request, optionally including the {@code emotionVector} (Zonos emotion
   * conditioning) and a {@code playerReferenceClip} local file path (Zonos custom player voice
   * cloning, issue #50). A {@code null} field is omitted from the JSON, so:
   *
   * <ul>
   *   <li>both {@code null} → byte-for-byte the base Kokoro/Azure request,
   *   <li>only the vector set → the standard Zonos request,
   *   <li>both set → a Zonos player-voice request whose reference clip the engine clones from.
   * </ul>
   *
   * The {@code playerReferenceClip} field is therefore absent for every NPC line and for every
   * non-Zonos backend.
   */
  static String encodeRequest(
      SynthesisRequest request, float[] emotionVector, String playerReferenceClip, Gson gson) {
    VoiceSpec spec = request.voice();
    JsonObject voice = new JsonObject();
    voice.addProperty("player", spec.player());
    voice.addProperty("race", spec.race().name());
    voice.addProperty("gender", spec.gender().name());

    JsonObject root = new JsonObject();
    root.addProperty("text", request.text());
    root.add("voice", voice);
    // Emotion is sent for protocol completeness; Kokoro ignores it (neutral-only by design).
    root.addProperty("emotion", request.emotion().name());
    root.addProperty("speed", 1.0f);
    if (emotionVector != null) {
      com.google.gson.JsonArray vec = new com.google.gson.JsonArray();
      for (float v : emotionVector) {
        vec.add(v);
      }
      root.add("emotionVector", vec);
    }
    if (playerReferenceClip != null && !playerReferenceClip.isEmpty()) {
      root.addProperty("playerReferenceClip", playerReferenceClip);
    }
    return gson.toJson(root);
  }

  /**
   * Reads the JSON header line then the exact PCM frame it announces. Returns {@code null} for an
   * {@code error} header. Throws {@link IOException} on EOF (process died) or a truncated frame so
   * the caller can restart.
   */
  private Pcm readResponse() throws IOException {
    String header = readHeaderLine();
    if (header == null) {
      throw new IOException("engine closed stdout before sending a response");
    }
    JsonObject obj;
    try {
      obj = gson.fromJson(header, JsonObject.class);
    } catch (RuntimeException e) {
      throw new IOException("unparseable engine header: " + abbreviate(header));
    }
    if (obj == null) {
      throw new IOException("empty engine header");
    }
    if (obj.has("error")) {
      String message = obj.get("error").isJsonNull() ? "" : obj.get("error").getAsString();
      log.warn("Engine reported error for request: {}", message);
      return null;
    }
    if (!obj.has("sampleRate") || !obj.has("samples")) {
      throw new IOException("engine header missing sampleRate/samples: " + abbreviate(header));
    }
    String format =
        obj.has("format") && !obj.get("format").isJsonNull()
            ? obj.get("format").getAsString()
            : FORMAT_F32LE;
    if (!FORMAT_F32LE.equals(format)) {
      throw new IOException("unexpected engine PCM format: " + format);
    }
    int sampleRate = obj.get("sampleRate").getAsInt();
    int samples = obj.get("samples").getAsInt();
    if (samples < 0 || sampleRate <= 0) {
      throw new IOException(
          "invalid engine header values sampleRate=" + sampleRate + " samples=" + samples);
    }
    float[] decoded = readSamples(samples);
    return new Pcm(decoded, sampleRate);
  }

  /**
   * Reads exactly {@code samples * 4} bytes from stdout and decodes them as little-endian float32.
   * {@link DataInputStream#readFully} loops over partial reads and throws {@link
   * java.io.EOFException} if the stream ends early (process death mid-frame).
   */
  private float[] readSamples(int samples) throws IOException {
    byte[] raw = new byte[samples * 4];
    fromEngine.readFully(raw);
    ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    float[] out = new float[samples];
    for (int i = 0; i < samples; i++) {
      out[i] = buf.getFloat();
    }
    return out;
  }

  /**
   * Reads a single newline-terminated header line as UTF-8 bytes off the raw stdout stream, leaving
   * the byte cursor exactly at the start of the PCM frame. A buffered reader cannot be used here
   * because it would over-read into the binary frame; this consumes byte-by-byte up to and
   * including the {@code \n} (tolerating a trailing {@code \r}). Returns {@code null} at EOF before
   * any byte.
   */
  private String readHeaderLine() throws IOException {
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(128);
    int b;
    boolean any = false;
    while ((b = fromEngine.read()) != -1) {
      any = true;
      if (b == '\n') {
        break;
      }
      buf.write(b);
    }
    if (!any) {
      return null;
    }
    String line = buf.toString("UTF-8");
    if (line.endsWith("\r")) {
      line = line.substring(0, line.length() - 1);
    }
    return line;
  }

  /** Best-effort drain of any buffered engine stderr into the log for diagnosing a crash. */
  private void drainStderr() {
    BufferedReader err = this.fromEngineErr;
    if (err == null) {
      return;
    }
    try {
      StringBuilder sb = new StringBuilder();
      while (err.ready()) {
        String l = err.readLine();
        if (l == null) {
          break;
        }
        sb.append(l).append('\n');
      }
      if (sb.length() > 0) {
        log.warn("Engine stderr:\n{}", sb.toString().trim());
      }
    } catch (IOException ignored) {
      // best-effort
    }
  }

  /** Stops the child process and releases its streams. Safe to call when already stopped. */
  public synchronized void stop() {
    stopQuietly();
  }

  private void stopQuietly() {
    Process p = this.process;
    if (p != null) {
      try {
        p.destroy();
        if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
          p.destroyForcibly();
        }
      } catch (InterruptedException e) {
        p.destroyForcibly();
        Thread.currentThread().interrupt();
      } catch (RuntimeException ignored) {
        // best-effort teardown
      }
    }
    this.process = null;
    this.toEngine = null;
    this.fromEngine = null;
    this.fromEngineErr = null;
  }

  private static String abbreviate(String s) {
    if (s == null) {
      return "null";
    }
    return s.length() <= 120 ? s : s.substring(0, 120) + "...";
  }
}
