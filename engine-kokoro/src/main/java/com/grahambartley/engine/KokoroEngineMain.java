package com.grahambartley.engine;

import com.grahambartley.engine.StdioProtocol.Request;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for the standalone Kokoro TTS engine.
 *
 * <p>Run with {@code --stdio} to speak the line protocol the plugin's {@code ExternalEngineClient}
 * drives: read one JSON request line from stdin, synthesize, and write a JSON header line plus a
 * little-endian float32 PCM frame to stdout, looping until stdin closes. Stderr carries human logs
 * only, so stdout stays a clean binary channel.
 *
 * <p>{@code --selftest} loads the model and synthesizes a short fixed phrase, printing the
 * resulting sample rate and sample count. It is the local smoke test used to confirm a freshly
 * built image on the host before the manual release workflow exercises the other targets.
 */
public final class KokoroEngineMain {

  private KokoroEngineMain() {}

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "";
    switch (mode) {
      case "--stdio":
        runStdio();
        break;
      case "--selftest":
        runSelfTest();
        break;
      default:
        System.err.println(
            "Usage: kokoro-engine --stdio | --selftest\n"
                + "  --stdio     line protocol: JSON request on stdin -> JSON header + f32le PCM on stdout\n"
                + "  --selftest  synthesize a fixed phrase and report sampleRate/samples");
        System.exit(2);
    }
  }

  private static void runStdio() throws IOException {
    KokoroEngine engine = new KokoroEngine();
    Runtime.getRuntime().addShutdownHook(new Thread(engine::close));
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    OutputStream out = System.out;

    String line;
    while ((line = in.readLine()) != null) {
      if (line.isEmpty()) {
        continue;
      }
      try {
        Request req = StdioProtocol.decodeRequest(line);
        KokoroEngine.Pcm pcm = engine.synthesize(req.text, req.speakerId(), req.speed);
        byte[] frame = StdioProtocol.encodeSamples(pcm.samples);
        StdioProtocol.writeResponse(out, pcm.sampleRate, frame);
      } catch (Exception e) {
        // Surface the failure as a parseable header so the plugin can recover without a hung pipe.
        String err = StdioProtocol.error(e.getMessage()) + System.lineSeparator();
        out.write(err.getBytes(StandardCharsets.UTF_8));
        out.flush();
        System.err.println("Synthesis failed: " + e.getMessage());
      }
    }
    engine.close();
  }

  private static void runSelfTest() {
    KokoroEngine engine = new KokoroEngine();
    try {
      KokoroEngine.Pcm pcm =
          engine.synthesize(
              "Conformance check. The engine is alive.",
              SpeakerMatrix.speakerId(false, "HUMAN", "MALE"),
              1.0f);
      System.out.println("sampleRate=" + pcm.sampleRate + " samples=" + pcm.samples.length);
      if (pcm.sampleRate <= 0 || pcm.samples.length == 0) {
        System.err.println("Self-test produced empty audio");
        System.exit(1);
      }
    } finally {
      engine.close();
    }
  }
}
