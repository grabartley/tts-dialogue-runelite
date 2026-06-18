package com.grahambartley.tts;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * In-process Kokoro TTS engine backed by sherpa-onnx.
 *
 * <p>The model is heavy to load (hundreds of MB) and synthesis is CPU-bound, so both run on a
 * single background thread and never on the game thread. The model is loaded lazily on first use
 * and reused for the lifetime of the engine.
 */
@Slf4j
public class KokoroTtsEngine {

  /** Mono PCM produced by Kokoro: float samples in [-1, 1] at the given sample rate (24 kHz). */
  public static final class Pcm {
    private final float[] samples;
    private final int sampleRate;

    public Pcm(float[] samples, int sampleRate) {
      this.samples = samples;
      this.sampleRate = sampleRate;
    }

    public float[] getSamples() {
      return samples;
    }

    public int getSampleRate() {
      return sampleRate;
    }
  }

  private final KokoroModelAssets assets;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "kokoro-tts");
            t.setDaemon(true);
            return t;
          });

  private volatile OfflineTts tts;
  private volatile boolean failed;
  private Future<?> current;

  public KokoroTtsEngine(Path baseDir) {
    this.assets = new KokoroModelAssets(baseDir);
  }

  /** Triggers model download and load on the background thread without blocking the caller. */
  public void prewarm() {
    executor.submit(this::ensureLoaded);
  }

  public boolean isFailed() {
    return failed;
  }

  /**
   * Synthesizes {@code text} off the game thread and hands the resulting PCM to {@code onAudio}.
   * Any line still queued behind a running synth is cancelled so skipped dialogue does not pile up.
   */
  public synchronized void speak(String text, int speakerId, Consumer<Pcm> onAudio) {
    if (failed) {
      return;
    }
    if (current != null) {
      current.cancel(false);
    }
    current =
        executor.submit(
            () -> {
              OfflineTts engine = ensureLoaded();
              if (engine == null) {
                return;
              }
              long start = System.nanoTime();
              GeneratedAudio audio = engine.generate(text, speakerId, 1.0f);
              long synthMs = (System.nanoTime() - start) / 1_000_000L;
              float[] samples = audio.getSamples();
              int sampleRate = audio.getSampleRate();
              double audioSeconds = sampleRate > 0 ? samples.length / (double) sampleRate : 0;
              log.info(
                  "Kokoro synth: {} ms for {} chars ({}s audio, sid {})",
                  synthMs,
                  text.length(),
                  String.format("%.2f", audioSeconds),
                  speakerId);
              onAudio.accept(new Pcm(samples, sampleRate));
            });
  }

  /** Cancels any queued synthesis (best effort; a running native generate is not interrupted). */
  public synchronized void interrupt() {
    if (current != null) {
      current.cancel(false);
      current = null;
    }
  }

  public void close() {
    executor.shutdownNow();
    OfflineTts engine = tts;
    if (engine != null) {
      try {
        engine.release();
      } catch (Throwable ignored) {
        // best-effort native cleanup
      }
      tts = null;
    }
  }

  private synchronized OfflineTts ensureLoaded() {
    if (tts != null) {
      return tts;
    }
    if (failed) {
      return null;
    }
    try {
      Path modelDir = assets.ensureAvailable();
      long start = System.nanoTime();

      OfflineTtsKokoroModelConfig kokoro =
          OfflineTtsKokoroModelConfig.builder()
              .setModel(modelDir.resolve("model.onnx").toString())
              .setVoices(modelDir.resolve("voices.bin").toString())
              .setTokens(modelDir.resolve("tokens.txt").toString())
              .setDataDir(modelDir.resolve("espeak-ng-data").toString())
              .setLexicon(modelDir.resolve("lexicon-us-en.txt").toString())
              .build();

      OfflineTtsModelConfig modelConfig =
          OfflineTtsModelConfig.builder()
              .setKokoro(kokoro)
              .setNumThreads(2)
              .setDebug(false)
              .setProvider("cpu")
              .build();

      OfflineTtsConfig config = OfflineTtsConfig.builder().setModel(modelConfig).build();

      OfflineTts engine = new OfflineTts(config);
      long loadMs = (System.nanoTime() - start) / 1_000_000L;
      log.info("Kokoro model loaded in {} ms ({} speakers)", loadMs, engine.getNumSpeakers());
      tts = engine;
      return tts;
    } catch (Throwable e) {
      failed = true;
      log.error("Failed to initialize in-process Kokoro TTS: {}", e.getMessage(), e);
      return null;
    }
  }
}
