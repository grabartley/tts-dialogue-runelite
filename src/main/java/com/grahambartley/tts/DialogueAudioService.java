package com.grahambartley.tts;

import com.grahambartley.synthesis.BackendProvider;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.SynthesisBackend;
import com.grahambartley.synthesis.SynthesisRequest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives synthesis and playback off the game thread.
 *
 * <p>The game thread only calls {@link #speak} / {@link #interrupt}; everything heavy runs on a
 * single background thread fed by a small bounded queue. Synthesis is delegated to the active
 * {@link SynthesisBackend} via {@link BackendProvider}, and repeated lines are served from an
 * {@link LruCache} keyed on {@code (backendId, voiceKey, emotion, text)} so a different backend,
 * voice, or emotion never serves stale audio. An epoch counter makes interruption clean: bumping it
 * on every new line and on {@link #interrupt} causes any queued or in-flight work for a now-stale
 * line to drop instead of playing.
 */
@Slf4j
public final class DialogueAudioService {

  /**
   * Identifies a synthesized line. The active backend, the resolved voice, the (possibly
   * downgraded) emotion, and the text are all part of the identity, so the same words spoken with a
   * different backend, voice, or emotion are distinct cache entries.
   */
  private record CacheKey(String backendId, String voiceKey, Emotion emotion, String text) {}

  private final BackendProvider backends;
  private final AudioOutput output;
  private final Executor executor;
  private final LruCache<CacheKey, Pcm> cache;
  private final IntSupplier volume;
  private final AtomicLong epoch = new AtomicLong();

  public DialogueAudioService(
      BackendProvider backends,
      AudioOutput output,
      int cacheSize,
      int queueCapacity,
      IntSupplier volume) {
    this(backends, output, buildExecutor(queueCapacity), cacheSize, volume);
  }

  /** Test seam: lets callers inject an inline executor so behavior is deterministic. */
  DialogueAudioService(
      BackendProvider backends,
      AudioOutput output,
      Executor executor,
      int cacheSize,
      IntSupplier volume) {
    this.backends = backends;
    this.output = output;
    this.executor = executor;
    this.cache = new LruCache<>(cacheSize);
    this.volume = volume;
  }

  /** Runs a one-off warm-up task (typically the model load) on the pipeline thread. */
  public void prewarm(Runnable warm) {
    submit(warm);
  }

  /**
   * Enqueues a line for synthesis and playback. Interrupts whatever is playing now so dialogue
   * advancement replaces the previous line rather than overlapping it.
   */
  public void speak(SynthesisRequest request) {
    if (request == null || request.text() == null || request.text().isEmpty()) {
      return;
    }
    long mine = epoch.incrementAndGet();
    output.stop();
    // Resolve the active backend now so the cache key reflects the backend that will actually run,
    // even if the user switches backend before this line reaches the pipeline thread.
    SynthesisBackend backend = backends.active();
    SynthesisRequest effective = BackendProvider.downgradeFor(backend, request);
    CacheKey key =
        new CacheKey(backend.id(), effective.voice().key(), effective.emotion(), effective.text());
    submit(() -> run(mine, backend, effective, key));
  }

  /** Stops current playback and drops any queued lines for the now-stale dialogue. */
  public void interrupt() {
    epoch.incrementAndGet();
    output.stop();
  }

  public void close() {
    epoch.incrementAndGet();
    output.stop();
    if (executor instanceof ExecutorService es) {
      es.shutdownNow();
      try {
        // Give an in-flight synth a brief window to unwind so a plugin reload does not orphan it.
        es.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    output.close();
  }

  private void run(long mine, SynthesisBackend backend, SynthesisRequest request, CacheKey key) {
    if (epoch.get() != mine) {
      return;
    }
    Pcm pcm = cache.get(key);
    if (pcm == null) {
      pcm = backends.synthesizeWith(backend, request);
      if (pcm == null) {
        return;
      }
      cache.put(key, pcm);
    } else {
      log.debug(
          "Serving \"{}\" ({}/{}) from cache",
          abbreviate(key.text()),
          key.backendId(),
          key.voiceKey());
    }
    // Re-check after the (possibly slow) synth: the line may have been skipped meanwhile.
    if (epoch.get() != mine) {
      return;
    }
    output.stream(pcm.getSamples(), pcm.getSampleRate(), volume.getAsInt());
  }

  private void submit(Runnable task) {
    try {
      executor.execute(task);
    } catch (RejectedExecutionException ignored) {
      // Queue saturated or shutting down; dropping is fine since newer lines supersede older ones.
    }
  }

  private static ExecutorService buildExecutor(int queueCapacity) {
    return new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        r -> {
          Thread t = new Thread(r, "dialogue-audio");
          t.setDaemon(true);
          return t;
        },
        // Drop the oldest queued line under backpressure (newer dialogue supersedes it), but log it
        // so QA can tell whether the queue is actually saturating in practice.
        (r, exec) -> {
          log.debug("Dialogue audio queue saturated; dropping oldest queued line");
          new ThreadPoolExecutor.DiscardOldestPolicy().rejectedExecution(r, exec);
        });
  }

  private static String abbreviate(String text) {
    return text.length() <= 40 ? text : text.substring(0, 40) + "...";
  }
}
