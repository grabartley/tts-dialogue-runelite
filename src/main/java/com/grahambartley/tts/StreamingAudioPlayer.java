package com.grahambartley.tts;

import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import lombok.extern.slf4j.Slf4j;

/**
 * Streams Kokoro PCM through a {@link SourceDataLine} instead of loading a whole {@code Clip}.
 *
 * <p>Audio is written to the line in small chunks straight from memory, so nothing is ever staged
 * to a temp file on disk. A generation counter lets {@link #stop()} interrupt the in-flight line:
 * the streaming loop bails as soon as a newer generation is observed.
 */
@Slf4j
public class StreamingAudioPlayer implements AudioOutput {

  /**
   * Supplies an unopened {@link SourceDataLine} for a format; a seam so tests can mock the line.
   */
  public interface LineFactory {
    SourceDataLine getLine(AudioFormat format) throws LineUnavailableException;
  }

  private static final int CHUNK_BYTES = 4096;

  private final LineFactory lineFactory;
  private final AtomicLong generation = new AtomicLong();
  private volatile SourceDataLine line;

  public StreamingAudioPlayer() {
    this(AudioSystem::getSourceDataLine);
  }

  StreamingAudioPlayer(LineFactory lineFactory) {
    this.lineFactory = lineFactory;
  }

  @Override
  public void stream(float[] samples, int sampleRate, int volumePercent) {
    if (samples == null || samples.length == 0) {
      return;
    }
    long gen = generation.incrementAndGet();
    byte[] pcm = KokoroAudio.toPcm16LE(samples);
    AudioFormat format = KokoroAudio.format(sampleRate);
    SourceDataLine open = null;
    try {
      open = lineFactory.getLine(format);
      open.open(format);
      applyVolume(open, volumePercent);
      open.start();
      this.line = open;

      int offset = 0;
      while (offset < pcm.length) {
        if (generation.get() != gen) {
          break;
        }
        int len = Math.min(CHUNK_BYTES, pcm.length - offset);
        // write() blocks until the line can accept more, pacing playback to real time.
        offset += open.write(pcm, offset, len);
      }
      if (generation.get() == gen) {
        open.drain();
      }
    } catch (Exception e) {
      log.warn("Audio playback failed: {}", e.getMessage());
    } finally {
      if (open != null) {
        try {
          open.stop();
          open.close();
        } catch (Exception ignored) {
          // best-effort line teardown
        }
      }
      if (this.line == open) {
        this.line = null;
      }
    }
  }

  @Override
  public void stop() {
    // Invalidate the current generation so the streaming loop bails, then unblock any pending
    // write() by flushing and stopping the line.
    generation.incrementAndGet();
    SourceDataLine current = this.line;
    if (current != null) {
      try {
        current.stop();
        current.flush();
      } catch (Exception ignored) {
        // best-effort interruption
      }
    }
  }

  @Override
  public void close() {
    stop();
  }

  private static void applyVolume(SourceDataLine line, int volumePercent) {
    if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
      return;
    }
    FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
    int volume = Math.max(0, Math.min(100, volumePercent));
    if (volume == 0) {
      gain.setValue(gain.getMinimum());
      return;
    }
    float db = (float) (20.0 * Math.log10(volume / 100.0));
    gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
  }
}
