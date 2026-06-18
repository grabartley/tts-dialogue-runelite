package com.grahambartley.tts;

import java.io.ByteArrayInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

/**
 * Pure conversion helpers for Kokoro audio output.
 *
 * <p>sherpa-onnx hands back mono {@code float} samples in the range [-1, 1]. {@code javax.sound}
 * needs signed 16-bit little-endian PCM, so these helpers bridge the two without touching any
 * native code, which keeps them straightforward to unit test.
 */
public final class KokoroAudio {

  private KokoroAudio() {}

  /** Converts mono float samples in [-1, 1] to signed 16-bit little-endian PCM bytes. */
  public static byte[] toPcm16LE(float[] samples) {
    byte[] pcm = new byte[samples.length * 2];
    for (int i = 0; i < samples.length; i++) {
      float clamped = Math.max(-1f, Math.min(1f, samples[i]));
      int s = Math.round(clamped * 32767f);
      pcm[2 * i] = (byte) (s & 0xff);
      pcm[2 * i + 1] = (byte) ((s >> 8) & 0xff);
    }
    return pcm;
  }

  /** Audio format for Kokoro PCM at the given sample rate: 16-bit, mono, signed, little-endian. */
  public static AudioFormat format(int sampleRate) {
    return new AudioFormat(sampleRate, 16, 1, true, false);
  }

  /** Wraps the float samples as a playable {@link AudioInputStream}. */
  public static AudioInputStream toAudioInputStream(float[] samples, int sampleRate) {
    byte[] pcm = toPcm16LE(samples);
    return new AudioInputStream(new ByteArrayInputStream(pcm), format(sampleRate), samples.length);
  }
}
