package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Validation behaviour of {@link PlayerVoiceClip}: a valid few-second WAV is accepted (returned as
 * an absolute path), while empty/missing/non-WAV/too-short/too-long inputs all fall back to the
 * bundled default ({@code null}) without throwing. Uses a temp dir so no real user files are
 * touched.
 */
public class PlayerVoiceClipTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  /** Writes a mono 16-bit PCM WAV of the given duration into the temp dir and returns it. */
  private File wav(String name, double seconds) throws IOException {
    int sampleRate = 16000;
    int frames = (int) (sampleRate * seconds);
    AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
    byte[] data = new byte[frames * 2];
    for (int i = 0; i < frames; i++) {
      short s = (short) (Math.sin(2 * Math.PI * 220 * i / sampleRate) * 8000);
      data[i * 2] = (byte) (s & 0xff);
      data[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
    }
    File out = tmp.newFile(name);
    try (AudioInputStream ais =
        new AudioInputStream(new java.io.ByteArrayInputStream(data), format, frames)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
    }
    return out;
  }

  @Test
  public void emptyPathResolvesToDefaultWithoutNotice() {
    AtomicInteger notices = new AtomicInteger();
    PlayerVoiceClip clip = new PlayerVoiceClip(m -> notices.incrementAndGet());
    assertNull("empty path means use the default player reference", clip.resolve(""));
    assertNull("blank/whitespace is also the default", clip.resolve("   "));
    assertNull("null is the default", clip.resolve(null));
    assertEquals("the default path must never fire a notice", 0, notices.get());
  }

  @Test
  public void validWavResolvesToAbsolutePath() throws IOException {
    File file = wav("voice.wav", 4.0);
    PlayerVoiceClip clip = new PlayerVoiceClip();
    String resolved = clip.resolve(file.getPath());
    assertNotNull("a valid few-second WAV must be accepted", resolved);
    assertEquals(file.getAbsolutePath(), resolved);
    assertNull("a valid clip is not a rejection reason", clip.reasonInvalid(file.getPath()));
  }

  @Test
  public void missingFileFallsBackWithNotice() {
    AtomicInteger notices = new AtomicInteger();
    PlayerVoiceClip clip = new PlayerVoiceClip(m -> notices.incrementAndGet());
    String missing = new File(tmp.getRoot(), "nope.wav").getPath();
    assertNull("a missing file falls back to the default", clip.resolve(missing));
    assertEquals("a configured-but-missing clip fires one notice", 1, notices.get());
  }

  @Test
  public void nonWavFileFallsBack() throws IOException {
    File text = tmp.newFile("notes.txt");
    Files.write(text.toPath(), "not audio".getBytes(StandardCharsets.UTF_8));
    PlayerVoiceClip clip = new PlayerVoiceClip();
    assertNull("a non-WAV file falls back to the default", clip.resolve(text.getPath()));
    assertNotNull("a non-WAV file is reported as a rejection", clip.reasonInvalid(text.getPath()));
  }

  @Test
  public void wrongExtensionButRealWavIsValidated() throws IOException {
    // Validation reads the actual audio, not the extension: a real WAV with a .bin name is
    // accepted.
    File file = wav("clip.bin", 3.0);
    assertNotNull(new PlayerVoiceClip().resolve(file.getPath()));
  }

  @Test
  public void tooShortClipFallsBack() throws IOException {
    File file = wav("short.wav", 0.3);
    PlayerVoiceClip clip = new PlayerVoiceClip();
    assertNull("a sub-second clip falls back to the default", clip.resolve(file.getPath()));
    assertTrue(clip.reasonInvalid(file.getPath()).contains("too short"));
  }

  @Test
  public void tooLongClipFallsBack() throws IOException {
    File file = wav("long.wav", PlayerVoiceClip.MAX_SECONDS + 5.0);
    PlayerVoiceClip clip = new PlayerVoiceClip();
    assertNull("an over-long clip falls back to the default", clip.resolve(file.getPath()));
    assertTrue(clip.reasonInvalid(file.getPath()).contains("too long"));
  }

  @Test
  public void switchingBackToValidClearsRejection() throws IOException {
    File valid = wav("ok.wav", 4.0);
    AtomicInteger notices = new AtomicInteger();
    PlayerVoiceClip clip = new PlayerVoiceClip(m -> notices.incrementAndGet());
    String missing = new File(tmp.getRoot(), "missing.wav").getPath();

    assertNull(clip.resolve(missing));
    assertNotNull(clip.resolve(valid.getPath()));
    assertEquals("only the bad value notified", 1, notices.get());
  }

  @Test
  public void neverThrowsOnGarbageInput() {
    PlayerVoiceClip clip = new PlayerVoiceClip();
    // A path to a directory must degrade to null (not a readable regular file), never throw.
    assertNull(clip.resolve(tmp.getRoot().getPath()));
    assertFalse(
        "a directory is not a usable clip", clip.reasonInvalid(tmp.getRoot().getPath()) == null);
  }
}
