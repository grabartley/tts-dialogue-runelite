package com.grahambartley.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Pure encode/decode helpers for the {@code --stdio} wire protocol, kept separate from the I/O loop
 * and the native engine so they can be unit-tested directly.
 *
 * <p>Protocol: the plugin writes one JSON request line on stdin
 *
 * <pre>{"text","voice":{race,gender,player},"emotion","speed"}</pre>
 *
 * and reads back one JSON header line
 *
 * <pre>{"sampleRate":24000,"samples":N,"format":"f32le"}</pre>
 *
 * immediately followed by {@code N*4} little-endian float32 bytes. {@code emotion} is accepted and
 * ignored (Kokoro is neutral-only by design); {@code speed} defaults to 1.0 when absent.
 */
final class StdioProtocol {

  static final String FORMAT = "f32le";

  private StdioProtocol() {}

  /** Encodes mono float samples to little-endian float32 bytes, the protocol PCM frame. */
  static byte[] encodeSamples(float[] samples) {
    ByteBuffer buf = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float s : samples) {
      buf.putFloat(s);
    }
    return buf.array();
  }

  /** Decodes a request line into the fields the engine needs. */
  static Request decodeRequest(String line) {
    Map<String, String> m = Json.parseRequest(line);
    String text = m.getOrDefault("text", "");
    boolean player = Boolean.parseBoolean(m.getOrDefault("voice.player", "false"));
    String race = m.get("voice.race");
    String gender = m.get("voice.gender");
    float speed = parseFloat(m.get("speed"), 1.0f);
    return new Request(text, player, race, gender, speed);
  }

  /** Writes the header line then the raw PCM frame to {@code out}, flushing once complete. */
  static void writeResponse(OutputStream out, int sampleRate, byte[] pcm) throws IOException {
    String header = Json.header(sampleRate, pcm.length / 4, FORMAT) + System.lineSeparator();
    out.write(header.getBytes(StandardCharsets.UTF_8));
    out.write(pcm);
    out.flush();
  }

  private static float parseFloat(String s, float fallback) {
    if (s == null || s.isEmpty()) {
      return fallback;
    }
    try {
      return Float.parseFloat(s);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** A decoded synthesis request. {@code emotion} is intentionally absent: it is ignored. */
  static final class Request {
    final String text;
    final boolean player;
    final String race;
    final String gender;
    final float speed;

    Request(String text, boolean player, String race, String gender, float speed) {
      this.text = text;
      this.player = player;
      this.race = race;
      this.gender = gender;
      this.speed = speed;
    }

    int speakerId() {
      return SpeakerMatrix.speakerId(player, race, gender);
    }
  }
}
