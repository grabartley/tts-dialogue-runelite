package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.grahambartley.tts.Pcm;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

/** Decoding OpenRouter's headerless 16-bit LE mono PCM into Pcm at a known sample rate. */
public class RawPcmDecoderTest {

  /** Builds the raw, headerless little-endian byte stream for the given 16-bit samples. */
  static byte[] raw(short[] samples) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (short s : samples) {
      out.write(s & 0xff);
      out.write((s >> 8) & 0xff);
    }
    return out.toByteArray();
  }

  @Test
  public void decodesSampleCountAndCarriesGivenRate() {
    short[] samples = {0, 16384, -16384, 32767, -32768};
    Pcm pcm = RawPcmDecoder.decode(raw(samples), 24_000);
    assertEquals("the caller-provided rate is carried through", 24_000, pcm.getSampleRate());
    assertEquals("one float per 16-bit sample", samples.length, pcm.getSamples().length);
  }

  @Test
  public void converts16BitToFloatRange() {
    short[] samples = {0, 16384, -16384, -32768};
    float[] f = RawPcmDecoder.decode(raw(samples), 24_000).getSamples();
    assertEquals(0f, f[0], 1e-6);
    assertEquals(0.5f, f[1], 1e-3);
    assertEquals(-0.5f, f[2], 1e-3);
    assertEquals(-1.0f, f[3], 1e-6);
    for (float v : f) {
      assertTrue("samples stay within [-1, 1]", v >= -1f && v <= 1f);
    }
  }

  @Test
  public void usesWhateverRateTheCallerPasses() {
    Pcm pcm = RawPcmDecoder.decode(raw(new short[] {1, 2, 3}), 48_000);
    assertEquals("the decoder does not coerce the rate", 48_000, pcm.getSampleRate());
  }

  @Test
  public void rejectsOddByteCount() {
    assertNull(
        "an odd byte count is not whole 16-bit samples",
        RawPcmDecoder.decode(new byte[] {1, 2, 3}, 24_000));
  }

  @Test
  public void rejectsNullEmptyOrBadRate() {
    assertNull(RawPcmDecoder.decode(null, 24_000));
    assertNull(RawPcmDecoder.decode(new byte[] {1}, 24_000));
    assertNull("a non-positive rate is rejected", RawPcmDecoder.decode(raw(new short[] {1}), 0));
  }
}
