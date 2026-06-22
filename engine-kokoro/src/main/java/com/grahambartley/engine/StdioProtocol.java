package com.grahambartley.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

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

  /**
   * Sentinel for an absent/unspecified explicit speaker id: fall back to the race/gender matrix.
   */
  static final int NO_SPEAKER_ID = -1;

  private static final Gson GSON = new Gson();

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
    JsonObject root = line == null ? new JsonObject() : GSON.fromJson(line, JsonObject.class);
    if (root == null) {
      root = new JsonObject();
    }
    String text = asString(root.get("text"), "");
    JsonObject voice =
        root.has("voice") && root.get("voice").isJsonObject()
            ? root.getAsJsonObject("voice")
            : new JsonObject();
    boolean player = voice.has("player") && voice.get("player").getAsBoolean();
    String race = asString(voice.get("race"), null);
    String gender = asString(voice.get("gender"), null);
    float speed =
        root.has("speed") && !root.get("speed").isJsonNull()
            ? root.get("speed").getAsFloat()
            : 1.0f;
    // Optional explicit Kokoro speaker id (per-NPC voice variety, issue #78). Absent or negative
    // means "not specified": speakerId() then falls back to the race/gender matrix, so an older
    // plugin that never sends this field keeps the exact pre-#78 behaviour.
    int explicitSpeakerId =
        root.has("speakerId") && !root.get("speakerId").isJsonNull()
            ? root.get("speakerId").getAsInt()
            : NO_SPEAKER_ID;
    return new Request(text, player, race, gender, speed, explicitSpeakerId);
  }

  /** Writes the header line then the raw PCM frame to {@code out}, flushing once complete. */
  static void writeResponse(OutputStream out, int sampleRate, byte[] pcm) throws IOException {
    String header = header(sampleRate, pcm.length / 4) + System.lineSeparator();
    out.write(header.getBytes(StandardCharsets.UTF_8));
    out.write(pcm);
    out.flush();
  }

  /**
   * The response header object the plugin reads before the PCM frame. Field insertion order
   * (sampleRate, samples, format) is preserved by Gson's compact serializer, so the emitted bytes
   * stay {@code {"sampleRate":24000,"samples":N,"format":"f32le"}} exactly as the #32 contract and
   * the conformance test expect.
   */
  static String header(int sampleRate, int samples) {
    JsonObject obj = new JsonObject();
    obj.addProperty("sampleRate", sampleRate);
    obj.addProperty("samples", samples);
    obj.addProperty("format", FORMAT);
    return GSON.toJson(obj);
  }

  /** An error object so a failed request still yields a parseable line on stdout. */
  static String error(String message) {
    JsonObject obj = new JsonObject();
    obj.addProperty("error", message == null ? "" : message);
    return GSON.toJson(obj);
  }

  private static String asString(com.google.gson.JsonElement el, String fallback) {
    return el == null || el.isJsonNull() ? fallback : el.getAsString();
  }

  /** A decoded synthesis request. {@code emotion} is intentionally absent: it is ignored. */
  static final class Request {
    final String text;
    final boolean player;
    final String race;
    final String gender;
    final float speed;

    /** Explicit per-NPC speaker id from the wire, or {@link #NO_SPEAKER_ID} when not specified. */
    final int explicitSpeakerId;

    Request(String text, boolean player, String race, String gender, float speed) {
      this(text, player, race, gender, speed, NO_SPEAKER_ID);
    }

    Request(
        String text,
        boolean player,
        String race,
        String gender,
        float speed,
        int explicitSpeakerId) {
      this.text = text;
      this.player = player;
      this.race = race;
      this.gender = gender;
      this.speed = speed;
      this.explicitSpeakerId = explicitSpeakerId;
    }

    /**
     * The Kokoro speaker id to synthesize with. The plugin's explicit per-NPC choice wins when
     * present (issue #78); otherwise this falls back to the shared race/gender {@link
     * SpeakerMatrix} exactly as before, so a plugin that never sends {@code speakerId} is
     * unaffected.
     */
    int speakerId() {
      if (explicitSpeakerId >= 0) {
        return explicitSpeakerId;
      }
      return SpeakerMatrix.speakerId(player, race, gender);
    }
  }
}
