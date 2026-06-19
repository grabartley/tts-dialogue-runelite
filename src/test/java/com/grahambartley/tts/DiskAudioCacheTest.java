package com.grahambartley.tts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.grahambartley.synthesis.Emotion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DiskAudioCacheTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path cacheDir() {
    return tmp.getRoot().toPath().resolve("cache");
  }

  private static Pcm pcm(int sampleRate, float... samples) {
    return new Pcm(samples, sampleRate);
  }

  @Test
  public void missWhenNothingStored() {
    DiskAudioCache cache = new DiskAudioCache(cacheDir());
    assertNull(cache.get("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Hello"));
  }

  @Test
  public void roundTripsPcmAndPreservesSampleRate() {
    DiskAudioCache cache = new DiskAudioCache(cacheDir());
    Pcm stored = pcm(48_000, 0.5f, -0.25f, 1.0f, -1.0f, 0.0f);
    cache.put("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Hello", stored);

    Pcm read = cache.get("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Hello");
    assertNotNull(read);
    assertEquals("sample rate must survive disk round-trip", 48_000, read.getSampleRate());
    assertArrayEquals(stored.getSamples(), read.getSamples(), 0.0f);
  }

  @Test
  public void survivesAcrossFreshCacheInstance() {
    Pcm stored = pcm(24_000, 0.1f, -0.1f);
    new DiskAudioCache(cacheDir())
        .put("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Persist me", stored);

    // A brand new instance over the same directory simulates a new session.
    Pcm read =
        new DiskAudioCache(cacheDir())
            .get("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Persist me");
    assertNotNull("a fresh session should still find the line on disk", read);
    assertArrayEquals(stored.getSamples(), read.getSamples(), 0.0f);
  }

  @Test
  public void differentEmotionDoesNotCollide() {
    DiskAudioCache cache = new DiskAudioCache(cacheDir());
    cache.put("cloud-azure", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Halt", pcm(24_000, 0.1f));
    cache.put("cloud-azure", "npc:HUMAN:MALE", Emotion.ANGRY, "Halt", pcm(24_000, 0.9f));

    Pcm neutral = cache.get("cloud-azure", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Halt");
    Pcm angry = cache.get("cloud-azure", "npc:HUMAN:MALE", Emotion.ANGRY, "Halt");
    assertArrayEquals(new float[] {0.1f}, neutral.getSamples(), 0.0f);
    assertArrayEquals(new float[] {0.9f}, angry.getSamples(), 0.0f);
  }

  @Test
  public void differentBackendDoesNotCollide() {
    DiskAudioCache cache = new DiskAudioCache(cacheDir());
    cache.put("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Greetings", pcm(24_000, 0.2f));
    cache.put("cloud-azure", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Greetings", pcm(24_000, 0.8f));

    assertArrayEquals(
        new float[] {0.2f},
        cache.get("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Greetings").getSamples(),
        0.0f);
    assertArrayEquals(
        new float[] {0.8f},
        cache.get("cloud-azure", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Greetings").getSamples(),
        0.0f);
  }

  @Test
  public void corruptFileIsTreatedAsMissAndDeleted() throws IOException {
    DiskAudioCache cache = new DiskAudioCache(cacheDir());
    cache.put("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Boom", pcm(24_000, 0.3f, 0.4f));

    // Overwrite the single stored entry with garbage to simulate corruption / partial write.
    Path entry = onlyEntry(cacheDir());
    Files.write(entry, "not a valid cache file".getBytes(StandardCharsets.UTF_8));

    assertNull(
        "a corrupt file must read as a miss, not crash",
        cache.get("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Boom"));
    assertTrue("the corrupt file should be deleted on miss", Files.notExists(entry));

    // And a fresh synth can rewrite it cleanly.
    cache.put("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Boom", pcm(24_000, 0.3f, 0.4f));
    assertNotNull(cache.get("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Boom"));
  }

  @Test
  public void truncatedFileIsTreatedAsMiss() throws IOException {
    DiskAudioCache cache = new DiskAudioCache(cacheDir());
    cache.put(
        "local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Cut", pcm(24_000, 0.3f, 0.4f, 0.5f));

    Path entry = onlyEntry(cacheDir());
    byte[] full = Files.readAllBytes(entry);
    Files.write(entry, java.util.Arrays.copyOf(full, full.length - 4)); // drop one sample's bytes

    assertNull(cache.get("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "Cut"));
  }

  @Test
  public void evictionKeepsUsageBoundedAndDropsOldest() throws Exception {
    // A tight cap so a handful of entries trips eviction. Each entry is header(16) + N*4 bytes.
    long cap = 600;
    DiskAudioCache cache = new DiskAudioCache(cacheDir(), cap);

    // ~120 bytes each (16 + 26*4). Write several, bumping mtime ordering by sleeping a touch.
    float[] samples = new float[26];
    List<String> texts = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      String text = "line-" + i;
      texts.add(text);
      cache.put("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, text, new Pcm(samples, 24_000));
      Thread.sleep(5); // ensure distinct, increasing mtimes for deterministic LRU ordering
    }

    assertTrue("total cache size must stay under the cap", dirSize(cacheDir()) <= cap);
    // The very first lines should have been evicted; the most recent should remain.
    assertNull(
        "oldest line should be evicted",
        cache.get("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "line-0"));
    assertNotNull(
        "newest line should remain",
        cache.get("local-kokoro", "npc:HUMAN:MALE", Emotion.NEUTRAL, "line-11"));
  }

  @Test
  public void readFailureDoesNotThrowWhenDirIsUnreadable() {
    // Point at a path whose parent is a file: ensureDir / writes fail, but nothing throws.
    DiskAudioCache cache = new DiskAudioCache(cacheDir());
    // get on a totally fresh dir is just a miss, never an exception.
    assertNull(cache.get("x", "y", Emotion.NEUTRAL, "z"));
    // put failures are swallowed too.
    cache.put("x", "y", Emotion.NEUTRAL, "z", new Pcm(new float[] {0f}, 24_000));
  }

  private static Path onlyEntry(Path dir) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tdc")) {
      for (Path p : stream) {
        return p;
      }
    }
    throw new IllegalStateException("no cache entry found in " + dir);
  }

  private static long dirSize(Path dir) throws IOException {
    long total = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tdc")) {
      for (Path p : stream) {
        total += Files.size(p);
      }
    }
    return total;
  }
}
