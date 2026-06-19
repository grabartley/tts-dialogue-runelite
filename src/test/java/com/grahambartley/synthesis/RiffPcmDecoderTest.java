package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.grahambartley.tts.Pcm;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

/** Decoding Azure's RIFF/PCM bytes into Pcm with the true sample rate. */
public class RiffPcmDecoderTest {

  /** Builds a minimal 16-bit mono WAV around the given samples at the given rate. */
  static byte[] wav(short[] samples, int sampleRate) {
    int dataLen = samples.length * 2;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    putAscii(out, "RIFF");
    putLeInt(out, 36 + dataLen);
    putAscii(out, "WAVE");
    putAscii(out, "fmt ");
    putLeInt(out, 16); // PCM fmt chunk size
    putLeShort(out, 1); // PCM
    putLeShort(out, 1); // mono
    putLeInt(out, sampleRate);
    putLeInt(out, sampleRate * 2); // byte rate
    putLeShort(out, 2); // block align
    putLeShort(out, 16); // bits per sample
    putAscii(out, "data");
    putLeInt(out, dataLen);
    for (short s : samples) {
      putLeShort(out, s & 0xffff);
    }
    return out.toByteArray();
  }

  private static void putAscii(ByteArrayOutputStream out, String s) {
    for (int i = 0; i < s.length(); i++) {
      out.write(s.charAt(i) & 0xff);
    }
  }

  private static void putLeInt(ByteArrayOutputStream out, int v) {
    out.write(v & 0xff);
    out.write((v >> 8) & 0xff);
    out.write((v >> 16) & 0xff);
    out.write((v >> 24) & 0xff);
  }

  private static void putLeShort(ByteArrayOutputStream out, int v) {
    out.write(v & 0xff);
    out.write((v >> 8) & 0xff);
  }

  @Test
  public void decodesSampleCountAndRate() {
    short[] samples = {0, 16384, -16384, 32767, -32768};
    Pcm pcm = RiffPcmDecoder.decode(wav(samples, 24_000));
    assertEquals("true sample rate carried through", 24_000, pcm.getSampleRate());
    assertEquals("one float per 16-bit sample", samples.length, pcm.getSamples().length);
  }

  @Test
  public void converts16BitToFloatRange() {
    short[] samples = {0, 16384, -16384, -32768};
    float[] f = RiffPcmDecoder.decode(wav(samples, 24_000)).getSamples();
    assertEquals(0f, f[0], 1e-6);
    assertEquals(0.5f, f[1], 1e-3);
    assertEquals(-0.5f, f[2], 1e-3);
    assertEquals(-1.0f, f[3], 1e-6); // -32768 / 32768
    for (float v : f) {
      assertTrue("samples stay within [-1, 1]", v >= -1f && v <= 1f);
    }
  }

  @Test
  public void preservesNonStandardSampleRate() {
    Pcm pcm = RiffPcmDecoder.decode(wav(new short[] {1, 2, 3}, 48_000));
    assertEquals("a different rate is not coerced to 24k", 48_000, pcm.getSampleRate());
  }

  @Test
  public void rejectsNonRiffBytes() {
    assertNull(RiffPcmDecoder.decode("not a wav file at all............".getBytes()));
  }

  @Test
  public void rejectsTooShortBytes() {
    assertNull(RiffPcmDecoder.decode(new byte[] {1, 2, 3}));
    assertNull(RiffPcmDecoder.decode(null));
  }
}
