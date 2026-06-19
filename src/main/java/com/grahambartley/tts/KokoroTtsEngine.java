package com.grahambartley.tts;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * In-process Kokoro TTS engine backed by sherpa-onnx.
 *
 * <p>The model is heavy to load (hundreds of MB) and synthesis is CPU-bound. This class is purely
 * the model: {@link #synthesize} runs synchronously on the caller's thread, and the {@link
 * DialogueAudioService} is responsible for keeping that call off the game thread. The model is
 * loaded lazily on first use and reused for the lifetime of the engine.
 *
 * <p>This is the engine wrapped by the {@code local-kokoro} {@link
 * com.grahambartley.synthesis.SynthesisBackend}; the dialogue pipeline never calls it directly.
 */
@Slf4j
public class KokoroTtsEngine {

  private final KokoroModelAssets assets;

  private volatile OfflineTts tts;
  private volatile boolean failed;

  public KokoroTtsEngine(Path baseDir) {
    this.assets = new KokoroModelAssets(baseDir);
  }

  /** Loads the model now (download + initialize). Intended to be called from a warm-up thread. */
  public void load() {
    ensureLoaded();
  }

  public boolean isFailed() {
    return failed;
  }

  /**
   * Synthesizes {@code text} for {@code speakerId} on the calling thread, returning the PCM or
   * {@code null} if the model failed to load.
   */
  public Pcm synthesize(String text, int speakerId) {
    OfflineTts engine = ensureLoaded();
    if (engine == null) {
      return null;
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
    return new Pcm(samples, sampleRate);
  }

  public void close() {
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
