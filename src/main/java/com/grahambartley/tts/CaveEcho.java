package com.grahambartley.tts;

/**
 * Adds a stone-cave echo to a dry {@link Pcm} buffer: a damped feedback comb (the
 * Schroeder/Freeverb building block) that layers the dry line with a few decaying, progressively
 * duller repeats. Pure local DSP applied at playback time, so cached audio stays dry and the cloud
 * backend is never re-billed for the effect.
 */
public final class CaveEcho {
  private CaveEcho() {}

  static final int DELAY_MS = 260;
  static final float FEEDBACK = 0.35f;
  // One-pole low-pass on the feedback path: stone absorbs highs, so each repeat is duller.
  static final float DAMPING = 0.40f;
  static final int MAX_TAIL_MS = 2000;

  /**
   * Returns a new, echoed {@link Pcm}. Never mutates {@code dry}: the cached buffer is shared by
   * reference.
   */
  public static Pcm apply(Pcm dry) {
    float[] in = dry.getSamples();
    int rate = dry.getSampleRate();
    int d = Math.max(1, Math.round(DELAY_MS * rate / 1000f));

    // Repeats decay by FEEDBACK each hop; append just enough tail to reach ~-60 dB, capped.
    int hops = (int) Math.ceil(Math.log(1e-3) / Math.log(FEEDBACK));
    int tail = Math.min(hops * d, MAX_TAIL_MS * rate / 1000);
    float[] out = new float[in.length + tail];

    float lp = 0f;
    for (int i = 0; i < out.length; i++) {
      float dryS = i < in.length ? in[i] : 0f;
      float delayed = i >= d ? out[i - d] : 0f;
      lp = DAMPING * lp + (1f - DAMPING) * delayed;
      float s = dryS + FEEDBACK * lp;
      out[i] = s < -1f ? -1f : (s > 1f ? 1f : s);
    }
    return new Pcm(out, rate);
  }
}
