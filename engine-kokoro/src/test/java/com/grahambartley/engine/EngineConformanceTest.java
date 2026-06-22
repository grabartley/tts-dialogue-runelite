package com.grahambartley.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * End-to-end {@code --stdio} conformance against the real engine process and the native model.
 *
 * <p>This is the acceptance test issue #36 calls for: feed a request to a built engine, then assert
 * a valid header followed by a correctly sized PCM frame. It launches the same {@code main} the
 * shipped image runs, so it covers the wire protocol and the native synthesis path together.
 *
 * <p>It is gated on {@code KOKORO_MODEL_DIR} pointing at an extracted model: without the ~349 MB
 * model the native engine cannot run, so the test is skipped (not failed) on runners that have not
 * staged it. The release workflow stages the model and sets the variable on the Linux runner, and a
 * developer can run it locally the same way.
 */
public class EngineConformanceTest {

  private static final Pattern HEADER =
      Pattern.compile("\\{\"sampleRate\":(\\d+),\"samples\":(\\d+),\"format\":\"(f32le)\"\\}");

  @Test
  public void builtEngineEmitsValidHeaderAndPcmFrame() throws Exception {
    String modelDir = System.getenv("KOKORO_MODEL_DIR");
    assumeTrue(
        "KOKORO_MODEL_DIR not set; skipping native engine conformance test", modelDir != null);

    String javaBin =
        System.getProperty("java.home")
            + java.io.File.separator
            + "bin"
            + java.io.File.separator
            + "java";
    String classpath = System.getProperty("java.class.path");

    ProcessBuilder pb =
        new ProcessBuilder(
            javaBin, "-cp", classpath, "com.grahambartley.engine.KokoroEngineMain", "--stdio");
    pb.environment().put("KOKORO_MODEL_DIR", modelDir);
    pb.redirectErrorStream(false);
    Process proc = pb.start();

    try {
      String request =
          "{\"text\":\"Conformance frame.\",\"voice\":{\"race\":\"HUMAN\",\"gender\":\"MALE\",\"player\":false},\"emotion\":\"NEUTRAL\",\"speed\":1.0}\n";
      OutputStream stdin = proc.getOutputStream();
      stdin.write(request.getBytes(StandardCharsets.UTF_8));
      stdin.flush();

      DataInputStream stdout = new DataInputStream(proc.getInputStream());
      String headerLine = readLine(stdout);
      Matcher m = HEADER.matcher(headerLine.trim());
      assertTrue("invalid header line: " + headerLine, m.matches());

      int sampleRate = Integer.parseInt(m.group(1));
      int samples = Integer.parseInt(m.group(2));
      assertEquals(24000, sampleRate);
      assertTrue("expected a non-empty PCM frame", samples > 0);

      byte[] frame = new byte[samples * 4];
      stdout.readFully(frame);
      assertEquals(samples * 4, frame.length);

      stdin.close();
    } finally {
      proc.destroy();
      proc.waitFor();
      drain(proc.getErrorStream());
    }
  }

  /**
   * Reads a single line one byte at a time. This is deliberate: a buffering reader (e.g.
   * BufferedReader/DataInputStream.readLine) would read ahead past the newline and swallow the
   * leading bytes of the binary PCM frame that immediately follows the header line. Reading byte by
   * byte stops exactly at the newline, leaving the float32 frame intact in the stream for the
   * subsequent {@code readFully}.
   */
  private static String readLine(InputStream in) throws Exception {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') {
        break;
      }
      if (c != '\r') {
        sb.append((char) c);
      }
    }
    return sb.toString();
  }

  private static void drain(InputStream err) {
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
      while (r.readLine() != null) {
        // discard engine stderr
      }
    } catch (Exception ignored) {
      // best effort
    }
  }
}
