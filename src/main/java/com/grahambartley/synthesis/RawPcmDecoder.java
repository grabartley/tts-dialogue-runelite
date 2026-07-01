package com.grahambartley.synthesis;

import com.grahambartley.tts.Pcm;

/**
 * Decodes the headerless audio bytes returned by OpenRouter's {@code response_format: "pcm"} into a
 * {@link Pcm}.
 *
 * <p>The body carries no RIFF/WAVE header: it is a bare stream of signed 16-bit little-endian mono
 * samples at a sample rate the caller already knows (24 kHz for the OpenRouter speech endpoint,
 * matching the rest of the pipeline). The decoder therefore takes the rate as an argument rather
 * than reading it from a header, and converts each 16-bit sample to a {@code float} in [-1, 1] so
 * {@code StreamingAudioPlayer} plays it at native pitch with no resampling.
 */
final class RawPcmDecoder {

  private RawPcmDecoder() {}

  /**
   * Decodes headerless signed 16-bit LE mono PCM at {@code sampleRate} to {@link Pcm}, or returns
   * {@code null} when the bytes are missing or not a whole number of 16-bit samples (so callers
   * fail the line gracefully).
   */
  static Pcm decode(byte[] pcm, int sampleRate) {
    if (pcm == null || pcm.length < 2 || sampleRate <= 0) {
      return null;
    }
    // An odd byte count cannot be whole 16-bit samples: the body is truncated or not 16-bit PCM.
    if ((pcm.length & 1) != 0) {
      return null;
    }
    int sampleCount = pcm.length / 2;
    float[] samples = new float[sampleCount];
    for (int i = 0; i < sampleCount; i++) {
      int lo = pcm[2 * i] & 0xff;
      int hi = pcm[2 * i + 1];
      short s = (short) ((hi << 8) | lo);
      samples[i] = s / 32768f;
    }
    return new Pcm(samples, sampleRate);
  }
}
