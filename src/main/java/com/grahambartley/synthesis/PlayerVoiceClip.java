package com.grahambartley.synthesis;

import java.io.File;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves and validates the user's optional custom player-voice reference clip (issue #50) for the
 * Local (GPU) Zonos backend.
 *
 * <p>Zonos is a zero-shot voice-cloning model, so a short reference {@code .wav} becomes the cloned
 * player voice. The user points {@code playerVoiceClipPath} at a local file; this class turns that
 * raw config string into either a validated absolute path (the clip the engine should clone from)
 * or {@code null} (fall back to the bundled default player reference). It never throws, so it is
 * safe to call from the synthesis pipeline thread, and it surfaces an explanation through a
 * one-time notice hook the first time a configured-but-unusable clip is seen rather than spamming
 * every line.
 *
 * <p>Validation is plugin-side and conservative: the file must exist, be readable, decode as a
 * supported PCM WAV via {@link AudioSystem}, and be a sane length for reference conditioning (a few
 * seconds). A too-short or too-long clip is rejected (fall back to default) rather than fed to the
 * model, since both produce poor clones. The engine performs its own decode-and-fallback safety on
 * top of this, so an edge case that slips past here still degrades gracefully audibly.
 *
 * <p>This carries no Zonos/torch dependency and is fully unit-testable from a temp directory.
 */
@Slf4j
public final class PlayerVoiceClip {

  /**
   * Minimum usable reference length. Zonos needs at least a short utterance to capture a speaker;
   * sub-second clips clone poorly, so they are rejected in favour of the bundled default.
   */
  static final double MIN_SECONDS = 1.0;

  /**
   * Maximum usable reference length. A reference clip is meant to be a few seconds; anything much
   * longer is almost certainly the wrong file (a song, a podcast) and only slows conditioning, so
   * it is rejected rather than clamped to keep behaviour predictable.
   */
  static final double MAX_SECONDS = 30.0;

  private final Consumer<String> notice;

  /**
   * Tracks the last raw config value we evaluated so the one-time notice fires once per distinct
   * bad value, and re-fires if the user edits the path to a different (still bad) one, rather than
   * either spamming every line or going silent forever after the first edit.
   */
  private volatile String lastEvaluatedRaw;

  private volatile boolean lastEvaluatedNotified;

  public PlayerVoiceClip() {
    this(msg -> {});
  }

  public PlayerVoiceClip(Consumer<String> notice) {
    this.notice = notice == null ? msg -> {} : notice;
  }

  /**
   * Resolves the configured path to a validated absolute clip path, or {@code null} to mean "use
   * the bundled default player reference".
   *
   * <p>An empty/blank value is the default (no notice). A non-empty but invalid value (missing,
   * unreadable, not a supported WAV, or out of the sane length range) returns {@code null} and
   * fires the notice once for that value. Never throws.
   *
   * @param rawPath the raw {@code playerVoiceClipPath} config string
   * @return absolute path to a usable reference clip, or {@code null} to fall back to the default
   */
  public String resolve(String rawPath) {
    String trimmed = rawPath == null ? "" : rawPath.trim();
    if (trimmed.isEmpty()) {
      // Empty is the normal default, not an error: clear any prior bad-value state and stay quiet.
      lastEvaluatedRaw = "";
      lastEvaluatedNotified = false;
      return null;
    }
    String rejection = reasonInvalid(trimmed);
    if (rejection == null) {
      // Valid: reset notice state so a later switch back to a bad value notifies again.
      lastEvaluatedRaw = trimmed;
      lastEvaluatedNotified = false;
      return new File(trimmed).getAbsolutePath();
    }
    notifyOnce(trimmed, rejection);
    return null;
  }

  /**
   * Returns {@code null} when the trimmed, non-empty path is a usable reference clip, or a short
   * human-readable reason when it is not. Package-private so the unit test can assert the specific
   * rejection cause without reaching into private state.
   */
  String reasonInvalid(String path) {
    File file = new File(path);
    if (!file.exists()) {
      return "file does not exist";
    }
    if (!file.isFile() || !file.canRead()) {
      return "file is not a readable regular file";
    }
    try {
      AudioFileFormat fmt = AudioSystem.getAudioFileFormat(file);
      if (fmt.getType() != AudioFileFormat.Type.WAVE) {
        return "not a WAV file (only .wav is supported)";
      }
      double seconds = durationSeconds(fmt);
      if (seconds > 0 && seconds < MIN_SECONDS) {
        return "clip is too short (" + format(seconds) + "s); use about 3-10 seconds";
      }
      if (seconds > MAX_SECONDS) {
        return "clip is too long (" + format(seconds) + "s); use about 3-10 seconds";
      }
      return null;
    } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
      return "not a supported audio file (only PCM WAV is supported)";
    } catch (java.io.IOException e) {
      return "could not read the file (" + e.getMessage() + ")";
    } catch (RuntimeException e) {
      // Defensive: a malformed header can surface as an unchecked exception from the decoder.
      return "could not decode the file as audio";
    }
  }

  /**
   * Best-effort clip duration in seconds from the decoded format, or {@code 0} when the length is
   * unknown (some WAV headers omit a frame count). An unknown length is treated as "do not reject
   * on length" so a valid-but-unusual file is not blocked on a missing field; the engine still
   * guards the real decode.
   */
  private static double durationSeconds(AudioFileFormat fmt) {
    long frameLength = fmt.getFrameLength();
    AudioFormat format = fmt.getFormat();
    float frameRate = format.getFrameRate();
    if (frameLength <= 0 || frameRate <= 0 || frameRate == AudioSystem.NOT_SPECIFIED) {
      return 0;
    }
    return frameLength / (double) frameRate;
  }

  private void notifyOnce(String rawValue, String reason) {
    String msg =
        "Custom player voice clip is not usable ("
            + reason
            + "); using the default player voice. Point Player Voice Clip at a clean few-second"
            + " .wav file.";
    log.info(msg);
    // Only push the user-facing notice once per distinct bad value.
    if (!rawValue.equals(lastEvaluatedRaw) || !lastEvaluatedNotified) {
      lastEvaluatedRaw = rawValue;
      lastEvaluatedNotified = true;
      notice.accept(msg);
    }
  }

  private static String format(double seconds) {
    return String.format(java.util.Locale.ROOT, "%.1f", seconds);
  }
}
