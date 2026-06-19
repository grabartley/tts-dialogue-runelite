package com.grahambartley.synthesis;

import com.grahambartley.tts.Pcm;

/**
 * Decodes the RIFF/WAV bytes returned by Azure's {@code riff-24khz-16bit-mono-pcm} output format
 * into a {@link Pcm}.
 *
 * <p>The body is a standard little-endian WAV: a {@code RIFF}/{@code WAVE} header, an {@code fmt }
 * chunk carrying the real sample rate, and a {@code data} chunk of signed 16-bit mono samples. The
 * decoder walks the chunk list to locate {@code fmt } and {@code data} (Azure may prepend other
 * chunks), reads the true sample rate from the header, and converts each 16-bit sample to a {@code
 * float} in [-1, 1]. Carrying the true sample rate means {@code AudioPlayer} plays the audio at its
 * native pitch with no resampling shift.
 */
final class RiffPcmDecoder {

  private RiffPcmDecoder() {}

  /**
   * Decodes WAV/RIFF PCM bytes to {@link Pcm}, or returns {@code null} when the bytes are not a
   * recognisable 16-bit PCM WAV (so callers fail the line gracefully).
   */
  static Pcm decode(byte[] wav) {
    if (wav == null || wav.length < 44) {
      return null;
    }
    if (!matches(wav, 0, "RIFF") || !matches(wav, 8, "WAVE")) {
      return null;
    }

    int sampleRate = 0;
    int bitsPerSample = 0;
    int channels = 0;
    int dataOffset = -1;
    int dataLength = 0;

    int pos = 12; // first chunk after "WAVE"
    while (pos + 8 <= wav.length) {
      String chunkId = readId(wav, pos);
      int chunkSize = readLeInt(wav, pos + 4);
      int body = pos + 8;
      if (chunkSize < 0 || body + chunkSize > wav.length) {
        // Truncated or malformed size; clamp the data chunk to what is actually present.
        chunkSize = wav.length - body;
      }
      if ("fmt ".equals(chunkId) && chunkSize >= 16) {
        channels = readLeShort(wav, body + 2);
        sampleRate = readLeInt(wav, body + 4);
        bitsPerSample = readLeShort(wav, body + 14);
      } else if ("data".equals(chunkId)) {
        dataOffset = body;
        dataLength = chunkSize;
        break;
      }
      // Chunks are word-aligned: an odd size is padded with one byte.
      pos = body + chunkSize + (chunkSize & 1);
    }

    if (dataOffset < 0 || sampleRate <= 0 || bitsPerSample != 16) {
      return null;
    }
    if (channels <= 0) {
      channels = 1;
    }

    int sampleCount = dataLength / 2; // 16-bit -> 2 bytes per sample
    float[] samples = new float[sampleCount];
    for (int i = 0; i < sampleCount; i++) {
      int lo = wav[dataOffset + 2 * i] & 0xff;
      int hi = wav[dataOffset + 2 * i + 1];
      short s = (short) ((hi << 8) | lo);
      samples[i] = s / 32768f;
    }

    // Down-mix interleaved stereo to mono if the format ever reports more than one channel; the
    // requested Azure format is mono, so this is a safety net rather than the common path.
    if (channels > 1) {
      samples = downmix(samples, channels);
    }
    return new Pcm(samples, sampleRate);
  }

  private static float[] downmix(float[] interleaved, int channels) {
    int frames = interleaved.length / channels;
    float[] mono = new float[frames];
    for (int f = 0; f < frames; f++) {
      float sum = 0f;
      for (int c = 0; c < channels; c++) {
        sum += interleaved[f * channels + c];
      }
      mono[f] = sum / channels;
    }
    return mono;
  }

  private static boolean matches(byte[] b, int off, String ascii) {
    if (off + ascii.length() > b.length) {
      return false;
    }
    for (int i = 0; i < ascii.length(); i++) {
      if ((b[off + i] & 0xff) != ascii.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private static String readId(byte[] b, int off) {
    return new String(
        new char[] {
          (char) (b[off] & 0xff),
          (char) (b[off + 1] & 0xff),
          (char) (b[off + 2] & 0xff),
          (char) (b[off + 3] & 0xff)
        });
  }

  private static int readLeInt(byte[] b, int off) {
    return (b[off] & 0xff)
        | ((b[off + 1] & 0xff) << 8)
        | ((b[off + 2] & 0xff) << 16)
        | ((b[off + 3] & 0xff) << 24);
  }

  private static int readLeShort(byte[] b, int off) {
    return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
  }
}
