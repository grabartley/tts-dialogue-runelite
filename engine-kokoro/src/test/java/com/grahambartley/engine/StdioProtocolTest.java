package com.grahambartley.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.engine.StdioProtocol.Request;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Framing conformance for the {@code --stdio} protocol: a request decodes to the right voice and a
 * synthesized frame round-trips through the header + little-endian float32 encoding the plugin's
 * {@code ExternalEngineClient} expects. This runs without the native model so it is part of the
 * normal test suite on every runner.
 */
public class StdioProtocolTest {

  @Test
  public void decodesNpcVoiceRequest() {
    Request req =
        StdioProtocol.decodeRequest(
            "{\"text\":\"hello there\",\"voice\":{\"race\":\"ELF\",\"gender\":\"FEMALE\",\"player\":false},\"emotion\":\"HAPPY\",\"speed\":1.0}");
    assertEquals("hello there", req.text);
    assertEquals(false, req.player);
    assertEquals("ELF", req.race);
    assertEquals("FEMALE", req.gender);
    assertEquals(1.0f, req.speed, 0.0001f);
    // ELF/FEMALE resolves to a concrete Kokoro speaker id from the shared matrix.
    assertEquals(21, req.speakerId());
  }

  @Test
  public void decodesPlayerVoiceAndIgnoresEmotion() {
    Request req =
        StdioProtocol.decodeRequest(
            "{\"text\":\"my move\",\"voice\":{\"race\":\"HUMAN\",\"gender\":\"MALE\",\"player\":true},\"emotion\":\"ANGRY\"}");
    assertTrue(req.player);
    assertEquals(16, req.speakerId()); // player male
    // Absent speed defaults to 1.0 so the engine never feeds a zero rate to sherpa-onnx.
    assertEquals(1.0f, req.speed, 0.0001f);
  }

  @Test
  public void encodesSamplesAsLittleEndianFloat32() {
    float[] samples = {0f, 1f, -1f, 0.5f};
    byte[] frame = StdioProtocol.encodeSamples(samples);
    assertEquals(samples.length * 4, frame.length);

    ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
    float[] decoded = new float[samples.length];
    for (int i = 0; i < samples.length; i++) {
      decoded[i] = buf.getFloat();
    }
    assertArrayEquals(samples, decoded, 0.0f);
  }

  @Test
  public void writesHeaderLineThenPcmFrame() throws Exception {
    float[] samples = {0.25f, -0.25f};
    byte[] pcm = StdioProtocol.encodeSamples(samples);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    StdioProtocol.writeResponse(out, 24000, pcm);

    byte[] written = out.toByteArray();
    String asText = new String(written, StandardCharsets.UTF_8);
    int newline = asText.indexOf('\n');
    assertTrue("response must contain a header line", newline >= 0);

    String header = asText.substring(0, newline).trim();
    assertTrue(header.contains("\"sampleRate\":24000"));
    assertTrue(header.contains("\"samples\":2"));
    assertTrue(header.contains("\"format\":\"f32le\""));

    // The bytes after the header line are exactly the PCM frame.
    int frameStart = newline + 1;
    byte[] tail = new byte[written.length - frameStart];
    System.arraycopy(written, frameStart, tail, 0, tail.length);
    assertArrayEquals(pcm, tail);
  }

  @Test
  public void unknownRaceFallsBackToHuman() {
    Request req =
        StdioProtocol.decodeRequest(
            "{\"text\":\"x\",\"voice\":{\"race\":\"BANANA\",\"gender\":\"MALE\",\"player\":false}}");
    assertEquals(14, req.speakerId()); // human male fallback
  }

  @Test
  public void explicitSpeakerIdIsHonoredOverTheMatrix() {
    // Per-NPC voice variety (issue #78): the plugin sends an explicit speakerId so two human-male
    // NPCs no longer both collapse to am_fenrir (14). The engine voices the exact id sent.
    Request req =
        StdioProtocol.decodeRequest(
            "{\"text\":\"hi\",\"voice\":{\"race\":\"HUMAN\",\"gender\":\"MALE\",\"player\":false},\"speakerId\":17}");
    assertEquals(17, req.speakerId());
  }

  @Test
  public void absentSpeakerIdFallsBackToTheMatrix() {
    // Backward compatibility: an older plugin that never sends speakerId keeps the matrix voice.
    Request req =
        StdioProtocol.decodeRequest(
            "{\"text\":\"hi\",\"voice\":{\"race\":\"HUMAN\",\"gender\":\"MALE\",\"player\":false}}");
    assertEquals(StdioProtocol.NO_SPEAKER_ID, req.explicitSpeakerId);
    assertEquals(14, req.speakerId()); // human male matrix fallback
  }

  @Test
  public void negativeSpeakerIdIsTreatedAsAbsent() {
    // A negative explicit id is a non-choice and must not be voiced; the matrix wins.
    Request req =
        StdioProtocol.decodeRequest(
            "{\"text\":\"hi\",\"voice\":{\"race\":\"ELF\",\"gender\":\"FEMALE\",\"player\":false},\"speakerId\":-1}");
    assertEquals(21, req.speakerId()); // elf female matrix value
  }
}
