package com.grahambartley.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CaveEchoTest {

  private static int delaySamples(int rate) {
    return Math.max(1, Math.round(CaveEcho.DELAY_MS * rate / 1000f));
  }

  @Test
  public void impulseDecaysAcrossSuccessiveTaps() {
    int rate = 24_000;
    int d = delaySamples(rate);
    float[] in = new float[] {1f};
    Pcm out = CaveEcho.apply(new Pcm(in, rate));
    float[] s = out.getSamples();

    float firstTap = Math.abs(s[d]);
    float secondTap = Math.abs(s[2 * d]);
    assertTrue("first echo tap should be audible", firstTap > 0f);
    assertTrue("echo should decay: second tap quieter than first", secondTap < firstTap);
  }

  @Test
  public void silenceInSilenceOut() {
    Pcm out = CaveEcho.apply(new Pcm(new float[256], 24_000));
    for (float v : out.getSamples()) {
      assertEquals(0f, v, 0f);
    }
  }

  @Test
  public void outputLengthIsInputPlusTail() {
    int rate = 24_000;
    int d = delaySamples(rate);
    int hops = (int) Math.ceil(Math.log(1e-3) / Math.log(CaveEcho.FEEDBACK));
    int tail = Math.min(hops * d, CaveEcho.MAX_TAIL_MS * rate / 1000);
    float[] in = new float[100];
    Pcm out = CaveEcho.apply(new Pcm(in, rate));
    assertEquals(in.length + tail, out.getSamples().length);
  }

  @Test
  public void everyOutputSampleIsWithinUnitRange() {
    float[] in = new float[2048];
    for (int i = 0; i < in.length; i++) {
      in[i] = i % 2 == 0 ? 1f : -1f;
    }
    Pcm out = CaveEcho.apply(new Pcm(in, 24_000));
    for (float v : out.getSamples()) {
      assertTrue("sample out of range: " + v, v >= -1f && v <= 1f);
    }
  }

  @Test
  public void inputArrayIsNotMutated() {
    float[] in = new float[] {0.5f, -0.5f, 0.25f};
    float[] copy = in.clone();
    CaveEcho.apply(new Pcm(in, 24_000));
    assertEquals(copy.length, in.length);
    for (int i = 0; i < in.length; i++) {
      assertEquals(copy[i], in[i], 0f);
    }
  }

  @Test
  public void delayScalesWithSampleRate() {
    for (int rate : new int[] {24_000, 48_000}) {
      int d = delaySamples(rate);
      float[] s = CaveEcho.apply(new Pcm(new float[] {1f}, rate)).getSamples();
      assertTrue("a tap should land at the rate-scaled delay for " + rate, Math.abs(s[d]) > 0f);
    }
  }
}
