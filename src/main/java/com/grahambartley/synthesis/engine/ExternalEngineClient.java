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
   * Synthesizes one request through the engine, returning the decoded {@link Pcm} or {@code null}
   * on failure (engine error line, process death, malformed frame). Restarts the engine first if it
   * has died since the last call. Must be called from the single pipeline thread.
   */
  public synchronized Pcm synthesize(SynthesisRequest request) {
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
      writeRequest(request);
      return readResponse();
    } catch (IOException e) {
      log.warn("External engine request failed ({}); tearing down for restart", e.getMessage());
      drainStderr();
      stopQuietly();
      return null;
    }
  }

  /** Encodes the request as the protocol JSON line and writes it to the engine's stdin. */
  private void writeRequest(SynthesisRequest request) throws IOException {
    String line = encodeRequest(request, gson);
    toEngine.write(line.getBytes(StandardCharsets.UTF_8));
    toEngine.write('\n');
    toEngine.flush();
  }

  /** Builds the one-line JSON request the engine's {@code StdioProtocol} decodes. */
  static String encodeRequest(SynthesisRequest request, Gson gson) {
    VoiceSpec spec = request.voice();
    JsonObject voice = new JsonObject();
    voice.addProperty("player", spec.player());
    voice.addProperty("race", spec.race().name());
    voice.addProperty("gender", spec.gender().name());

    JsonObject root = new JsonObject();
    root.addProperty("text", request.text());
    root.add("voice", voice);
    // Per-NPC voice variety (issue #78): when the plugin picked an explicit Kokoro speaker for this
    // NPC, send it so the engine voices that exact speaker instead of recomputing
    // one-per-race/gender
    // from its matrix. Omitted entirely when absent, so the line is byte-for-byte the pre-#78
    // request
    // and an engine that ignores the field still falls back to its matrix.
    if (spec.hasExplicitKokoroSpeakerId()) {
      root.addProperty("speakerId", spec.kokoroSpeakerId());
    }
    // Emotion is sent for protocol completeness; Kokoro ignores it (neutral-only by design).
    root.addProperty("emotion", request.emotion().name());
    root.addProperty("speed", 1.0f);
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
