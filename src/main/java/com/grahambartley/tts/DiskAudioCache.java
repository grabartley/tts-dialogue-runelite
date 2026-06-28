package com.grahambartley.tts;

import com.grahambartley.synthesis.Emotion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * A persistent, on-disk synthesis cache that lets repeated dialogue lines survive across sessions.
 *
 * <p>It sits behind the in-memory {@link LruCache} in {@link DialogueAudioService} as the second
 * lookup tier: in-memory LRU → disk → synthesize. Its headline purpose is to keep cloud backends
 * (OpenRouter) from re-billing for lines a user has already heard; every backend also gets faster
 * replays for free.
 *
 * <p>Each entry is keyed on the full identity tuple {@code (backendId, voiceKey, emotion, text)} —
 * the same tuple {@link DialogueAudioService}'s in-memory {@code CacheKey} uses — hashed with
 * SHA-256 to derive a fixed-length, filesystem-safe filename. Different backends, voices, emotions,
 * or texts therefore never collide on disk.
 *
 * <p>Audio is stored in a tiny self-describing binary format (a 16-byte header carrying the sample
 * rate and sample count, then the float samples as little-endian float32) so a decoded entry always
 * carries the correct {@link Pcm} sample rate. This matters because backends synthesize at
 * different rates (Kokoro 24 kHz, the cloud backend may differ) and the player must not pitch-shift
 * a cached line.
 *
 * <p>Everything here is corruption-safe and never throws into the pipeline: writes go to a temp
 * file and are atomically renamed into place (no partial files), and any read that hits a missing,
 * truncated, or undecodable file is treated as a plain miss (and the bad file is deleted so the
 * next synth can rewrite it). All I/O is meant to run on the existing pipeline executor thread,
 * never the game thread.
 *
 * <p>Disk usage is bounded by a configurable total-size cap enforced with FIFO eviction: after a
 * write that pushes the directory over the cap, the oldest entries by write time are deleted until
 * usage is back under the limit. Eviction is purely first-in-first-out, so a read never rescues an
 * old entry from being dropped; the just-written entry always survives because it is the newest.
 */
@Slf4j
public final class DiskAudioCache {

  /**
   * Magic + version prefix so a future format change can be detected and treated as a miss rather
   * than mis-decoded. Four ASCII bytes "TDC1" (TTS Dialogue Cache v1).
   */
  private static final int MAGIC = 0x54_44_43_31; // "TDC1"

  /** Header is magic(4) + sampleRate(4) + sampleCount(4) + reserved(4) = 16 bytes. */
  private static final int HEADER_BYTES = 16;

  /**
   * Default total-size cap for the cache directory. 256 MiB holds on the order of thousands of
   * typical dialogue lines (a few seconds of 24 kHz mono float32 is ~100-300 KB each) while staying
   * a negligible slice of any modern disk, so users effectively never re-synthesize repeated lines
   * yet the directory can never grow without bound.
   */
  public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

  /**
   * Sentinel for an uncapped cache: a non-positive {@code maxBytes} disables eviction entirely, so
   * the cache keeps every clip and grows only with what the user actually hears. Opt-in for users
   * who would rather spend disk than ever re-bill a cloud line.
   */
  public static final long UNLIMITED = 0;

  private final Path dir;
  private final long maxBytes;
  private final boolean unlimited;

  /** Set once the directory is known unusable, so we stop retrying I/O every line. */
  private volatile boolean disabled;

  public DiskAudioCache(Path dir) {
    this(dir, DEFAULT_MAX_BYTES);
  }

  public DiskAudioCache(Path dir, long maxBytes) {
    this.dir = dir;
    this.unlimited = maxBytes <= UNLIMITED;
    this.maxBytes = maxBytes;
  }

  /**
   * Returns the cached audio for this key, or {@code null} on any miss (absent, corrupt, or I/O
   * error). A corrupt/undecodable file is deleted so the caller's write-through can replace it. A
   * hit deliberately does not bump the file's mtime: eviction is FIFO by write time, so a read must
   * not extend an entry's lifetime.
   */
  public Pcm get(String backendId, String voiceKey, Emotion emotion, String text) {
    if (disabled) {
      return null;
    }
    Path file;
    try {
      file = fileFor(backendId, voiceKey, emotion, text);
    } catch (RuntimeException e) {
      return null;
    }
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      Pcm pcm = decode(Files.readAllBytes(file));
      if (pcm == null) {
        // Truncated or wrong-magic file: drop it so a fresh synth can rewrite a clean copy.
        deleteQuietly(file);
        return null;
      }
      return pcm;
    } catch (IOException | RuntimeException e) {
      log.debug("Disk cache read failed for {}; treating as miss", file.getFileName(), e);
      deleteQuietly(file);
      return null;
    }
  }

  /**
   * Writes the audio through to disk for this key. Uses a temp file + atomic rename so a reader
   * never sees a partial file, then enforces the size cap. Failures are swallowed: a cache that
   * cannot write must not break playback.
   */
  public void put(String backendId, String voiceKey, Emotion emotion, String text, Pcm pcm) {
    if (disabled || pcm == null || pcm.getSamples() == null) {
      return;
    }
    Path file;
    try {
      ensureDir();
      file = fileFor(backendId, voiceKey, emotion, text);
    } catch (IOException | RuntimeException e) {
      log.debug("Disk cache unavailable; disabling on-disk caching", e);
      disabled = true;
      return;
    }
    Path tmp = null;
    try {
      tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
      Files.write(tmp, encode(pcm));
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException atomicUnsupported) {
        // Some filesystems reject ATOMIC_MOVE; fall back to a plain replace. Still far better than
        // writing the destination in place, which could leave a partial file on a crash.
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      tmp = null;
      enforceSizeCap();
    } catch (IOException | RuntimeException e) {
      log.debug("Disk cache write failed for {}; skipping", file.getFileName(), e);
    } finally {
      if (tmp != null) {
        deleteQuietly(tmp);
      }
    }
  }

  /** Visible for tests: the resolved cache directory. */
  Path directory() {
    return dir;
  }

  /** Resolves the on-disk file for a key from the SHA-256 of the full tuple. */
  private Path fileFor(String backendId, String voiceKey, Emotion emotion, String text) {
    return dir.resolve(hashKey(backendId, voiceKey, emotion, text) + ".tdc");
  }

  /**
   * Hashes the full key tuple. Fields are length-prefixed and joined with a delimiter that the
   * length prefix makes irrelevant, so {@code ("ab","c")} and {@code ("a","bc")} can never produce
   * the same digest.
   */
  private static String hashKey(String backendId, String voiceKey, Emotion emotion, String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      feed(md, backendId);
      feed(md, voiceKey);
      feed(md, emotion == null ? "null" : emotion.name());
      feed(md, text);
      byte[] digest = md.digest();
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated on every JRE; if it is somehow missing the cache cannot function.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void feed(MessageDigest md, String field) {
    byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
    ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length);
    md.update(len.array());
    md.update(bytes);
  }

  private static byte[] encode(Pcm pcm) {
    float[] samples = pcm.getSamples();
    ByteBuffer buf =
        ByteBuffer.allocate(HEADER_BYTES + samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(MAGIC);
    buf.putInt(pcm.getSampleRate());
    buf.putInt(samples.length);
    buf.putInt(0); // reserved
    for (float s : samples) {
      buf.putFloat(s);
    }
    return buf.array();
  }

  /**
   * Decodes a stored entry, or returns {@code null} if the bytes are not a valid, complete entry.
   */
  private static Pcm decode(byte[] bytes) {
    if (bytes.length < HEADER_BYTES) {
      return null;
    }
    ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (buf.getInt() != MAGIC) {
      return null;
    }
    int sampleRate = buf.getInt();
    int sampleCount = buf.getInt();
    buf.getInt(); // reserved
    if (sampleRate <= 0 || sampleCount < 0) {
      return null;
    }
    if (bytes.length != HEADER_BYTES + (long) sampleCount * 4) {
      // Truncated or trailing garbage: the declared sample count does not match the payload.
      return null;
    }
    float[] samples = new float[sampleCount];
    for (int i = 0; i < sampleCount; i++) {
      samples[i] = buf.getFloat();
    }
    return new Pcm(samples, sampleRate);
  }

  private void ensureDir() throws IOException {
    if (!Files.isDirectory(dir)) {
      Files.createDirectories(dir);
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // Best effort.
    }
  }

  /**
   * Deletes oldest-first (by write time) entries until the directory's total size is back under the
   * cap, so the cache never persists more than its limit. Only {@code .tdc} entries count; stray
   * temp files are ignored (they are short-lived and cleaned up by their own writers). A no-op for
   * an {@link #UNLIMITED} cache, which never evicts.
   */
  private void enforceSizeCap() {
    if (unlimited) {
      return;
    }
    List<Entry> entries = new ArrayList<>();
    long total = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tdc")) {
      for (Path p : stream) {
        try {
          BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
          if (attrs.isRegularFile()) {
            entries.add(new Entry(p, attrs.size(), attrs.lastModifiedTime().toMillis()));
            total += attrs.size();
          }
        } catch (IOException ignored) {
          // File vanished or is unreadable; skip it.
        }
      }
    } catch (IOException e) {
      log.debug("Disk cache size scan failed; skipping eviction this round", e);
      return;
    }
    if (total <= maxBytes) {
      return;
    }
    entries.sort(Comparator.comparingLong(e -> e.mtime)); // oldest first
    for (Entry e : entries) {
      if (total <= maxBytes) {
        break;
      }
      deleteQuietly(e.path);
      total -= e.size;
    }
  }

  private static final class Entry {
    final Path path;
    final long size;
    final long mtime;

    Entry(Path path, long size, long mtime) {
      this.path = path;
      this.size = size;
      this.mtime = mtime;
    }
  }
}
