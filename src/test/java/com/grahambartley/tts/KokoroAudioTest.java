package com.grahambartley.tts;

import static org.junit.Assert.assertEquals;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import org.junit.Test;

public class KokoroAudioTest {

  @Test
  public void producesTwoBytesPerSample() {
    byte[] pcm = KokoroAudio.toPcm16LE(new float[] {0f, 0f, 0f});
    assertEquals(6, pcm.length);
  }

  @Test
  public void encodesSilenceAsZero() {
    byte[] pcm = KokoroAudio.toPcm16LE(new float[] {0f});
    assertEquals(0, pcm[0]);
    assertEquals(0, pcm[1]);
  }

  @Test
  public void usesLittleEndianByteOrder() {
    // +1.0 maps to 32767 = 0x7FFF -> low byte 0xFF, high byte 0x7F
    byte[] pcm = KokoroAudio.toPcm16LE(new float[] {1f});
    assertEquals((byte) 0xFF, pcm[0]);
    assertEquals((byte) 0x7F, pcm[1]);
  }

  @Test
  public void clampsValuesAboveOne() {
    byte[] pcm = KokoroAudio.toPcm16LE(new float[] {2f});
    assertEquals((byte) 0xFF, pcm[0]);
    assertEquals((byte) 0x7F, pcm[1]);
  }

  @Test
  public void clampsValuesBelowNegativeOne() {
    // -1.0 maps to -32767 = 0x8001 -> low byte 0x01, high byte 0x80
    byte[] pcm = KokoroAudio.toPcm16LE(new float[] {-5f});
    assertEquals((byte) 0x01, pcm[0]);
    assertEquals((byte) 0x80, pcm[1]);
  }

  @Test
  public void formatIsMono16BitSignedLittleEndian() {
    AudioFormat format = KokoroAudio.format(24_000);
    assertEquals(24_000f, format.getSampleRate(), 0.0f);
    assertEquals(16, format.getSampleSizeInBits());
    assertEquals(1, format.getChannels());
    assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
    assertEquals(false, format.isBigEndian());
  }

  @Test
  public void audioStreamFrameLengthMatchesSampleCount() {
    AudioInputStream stream =
        KokoroAudio.toAudioInputStream(new float[] {0.1f, -0.1f, 0.5f}, 24_000);
    assertEquals(3, stream.getFrameLength());
    assertEquals(24_000f, stream.getFormat().getSampleRate(), 0.0f);
  }
}
