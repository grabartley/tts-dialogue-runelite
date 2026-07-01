package com.grahambartley.tts;

import com.grahambartley.synthesis.BackendProvider;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.SynthesisBackend;
import com.grahambartley.synthesis.SynthesisRequest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives synthesis and playback off the game thread.
 *
 * <p>The game thread only calls {@link #speak} / {@link #interrupt}; everything heavy runs on a
 * small background pool ({@code SYNTH_THREADS} workers) fed by a bounded queue, so a line stuck
 * retrying a slow cloud call does not stall the next line. Synthesis is delegated to the active
 * {@link SynthesisBackend} via {@link BackendProvider}, and repeated lines are served from an
 * {@link LruCache} keyed on {@code (backendId, voiceKey, emotion, text)} so a different backend,
 * voice, or emotion never serves stale audio. Behind that sits an optional persistent {@link
 * DiskAudioCache} keyed on the same tuple, so lines survive across sessions and cloud backends are
 * not re-billed for audio the user has already heard. Lookup order is: in-memory LRU → disk →
 * synthesize, writing through to both tiers on a synth and promoting disk hits into memory. An
 * epoch counter makes interruption clean: bumping it on every new line and on {@link #interrupt}
 * causes any queued or in-flight work for a now-stale line to drop instead of playing.
 *
 * <p>A small in-flight registry de-duplicates concurrent synthesis: if two tasks reach the synth
 * step for the same {@code CacheKey} at once, only the first calls the backend and the second waits
 * on its result, so a billable cloud line is never paid for twice in parallel.
 */
@Slf4j
public final class DialogueAudioService {

  /**
   * Identifies a synthesized line. The active backend, the resolved voice, the (possibly
   * downgraded) emotion, and the text are all part of the identity, so the same words spoken with a
   * different backend, voice, or emotion are distinct cache entries.
   */
  @Value
  @Accessors(fluent = true)
  static class CacheKey {
    String backendId;
    String voiceKey;
    Emotion emotion;
    String text;
  }

  /**
   * Worker threads for the live synthesis pool. Two, sharing one queue, so a line stuck retrying a
   * slow cloud call (which is left running so its result still caches) does not block the next
   * line: the free worker picks it up. Concurrent same-key calls are still de-duped, cloud calls
   * each get their own pooled HTTP/1.1 connection, and the local engine's synth round-trip is
   * synchronized, so two workers never contend on shared state (#196).
   */
  private static final int SYNTH_THREADS = 2;

  /**
   * Hard ceiling on concurrent prefetch synths, so speculative warming never floods the backend.
   */
  private static final int PREFETCH_THREADS = 2;

  /**
   * Bounded prefetch backlog; excess speculative work is dropped rather than queued unboundedly.
   */
  private static final int PREFETCH_QUEUE_CAPACITY = 16;

  /** Character budget for the abbreviated text preview in debug logs. */
  private static final int LOG_TEXT_PREVIEW_LENGTH = 40;

  private final BackendProvider backends;
  private final AudioOutput output;
  private final Executor executor;
  // Engine install/model-load runs here, NOT on the synthesis pool, so a long bundle
  // download cannot block dialogue playback through an already-warm backend.
  private final Executor warmExecutor;
  // Speculative dialogue-tree prefetch runs here, off the synthesis pool, so warming the
  // next likely line never delays the line the player is actually hearing. Small fixed pool so no
  // more than PREFETCH_THREADS prefetch calls are ever in flight at once.
  private final Executor prefetchExecutor;
  private final LruCache<CacheKey, Pcm> cache;
  private final DiskAudioCache diskCache;
  private final IntSupplier volume;
  private final AtomicLong epoch = new AtomicLong();
  // Bumped on dialogue close / NPC change so prefetch tasks queued for the old node drop instead of
  // spending on branches the player has already left. Separate from the playback epoch: a new
  // spoken line must never cancel prefetch, and a prefetch must never cancel playback.
  private final AtomicLong prefetchEpoch = new AtomicLong();
  // Synths currently running, keyed by CacheKey, so a second task for the same line reuses the
  // pending result instead of issuing a duplicate (billable) backend call.
  private final ConcurrentHashMap<CacheKey, CompletableFuture<Pcm>> inFlight =
      new ConcurrentHashMap<>();

  public DialogueAudioService(
      BackendProvider backends,
      AudioOutput output,
      DiskAudioCache diskCache,
      int cacheSize,
      int queueCapacity,
      IntSupplier volume) {
    this(
        backends,
        output,
        diskCache,
        buildExecutor(queueCapacity),
        buildWarmExecutor(),
        buildPrefetchExecutor(),
        cacheSize,
        volume);
  }

  /**
   * Test seam: lets callers inject an inline executor and disk cache so behavior is deterministic.
   * The same executor backs warm-up and prefetch so tests stay single-threaded and deterministic.
   */
  DialogueAudioService(
      BackendProvider backends,
      AudioOutput output,
      DiskAudioCache diskCache,
      Executor executor,
      int cacheSize,
      IntSupplier volume) {
    this(backends, output, diskCache, executor, executor, executor, cacheSize, volume);
  }

  private DialogueAudioService(
      BackendProvider backends,
      AudioOutput output,
      DiskAudioCache diskCache,
      Executor executor,
      Executor warmExecutor,
      Executor prefetchExecutor,
      int cacheSize,
      IntSupplier volume) {
    this.backends = backends;
    this.output = output;
    this.diskCache = diskCache;
    this.executor = executor;
    this.warmExecutor = warmExecutor;
    this.prefetchExecutor = prefetchExecutor;
    this.cache = new LruCache<>(cacheSize);
    this.volume = volume;
  }

  /**
   * Runs a one-off warm-up task (engine install/model load) on the dedicated warm-up thread, kept
   * separate from the synthesis pool so a long engine download never blocks dialogue playback
   * through an already-warm backend.
   */
  public void prewarm(Runnable warm) {
    warmExecutor.execute(warm);
  }

  /**
   * Enqueues a line for synthesis and playback. Interrupts whatever is playing now so dialogue
   * advancement replaces the previous line rather than overlapping it.
   */
  public void speak(SynthesisRequest request) {
    speak(request, false);
  }

  /**
   * As {@link #speak(SynthesisRequest)}, but renders a cave echo over the dry audio just before
   * playback when {@code applyEcho} is set. The echo is pure local DSP on a fresh buffer: both
   * cache tiers still store the dry line under the unchanged key, so toggling the effect never
   * invalidates the cache and the cloud backend is never re-billed.
   */
  public void speak(SynthesisRequest request, boolean applyEcho) {
    if (request == null || request.text() == null || request.text().isEmpty()) {
      return;
    }
    long mine = epoch.incrementAndGet();
    output.stop();
    // Resolve the active backend now so the cache key reflects the backend that will actually run,
    // even if the user switches backend before this line reaches the pipeline thread.
    SynthesisBackend backend = backends.active();
    SynthesisRequest effective = BackendProvider.downgradeFor(backend, request);
    CacheKey key = keyFor(backend, effective);
    submit(() -> run(mine, backend, effective, key, applyEcho));
  }

  /**
   * Speculatively synthesizes a line the player is likely to hear next (a visible dialogue option),
   * warming both cache tiers so the real line plays from cache when it arrives. Unlike {@link
   * #speak}, it never plays audio and never touches the playback epoch, so it cannot interrupt or
   * be interrupted by the line currently playing. It shares the same in-flight dedup and both cache
   * tiers as {@link #speak}, so a prefetch and a real line for the same key never both bill the
   * backend, and an already-cached line is a cheap no-op. Skipped when the active backend is
   * unavailable or rate-limit backing off, so speculation never piles onto a 429.
   */
  public void prefetch(SynthesisRequest request) {
    if (request == null || request.text() == null || request.text().isEmpty()) {
      return;
    }
    SynthesisBackend backend = backends.active();
    if (!backend.isAvailable() || backend.isThrottled()) {
      return;
    }
    SynthesisRequest effective = BackendProvider.downgradeFor(backend, request);
    CacheKey key = keyFor(backend, effective);
    if (lookup(key) != null) {
      return;
    }
    long node = prefetchEpoch.get();
    submitPrefetch(
        () -> {
          // The player may have left this node, the backend may have hit a limit, or a real line
          // may
          // have warmed this key while we waited in the queue: re-check all three before spending.
          if (prefetchEpoch.get() != node || backend.isThrottled() || lookup(key) != null) {
            return;
          }
          log.debug(
              "[TTS synth] prefetch ({}/{}) \"{}\"",
              key.backendId(),
              key.voiceKey(),
              abbreviate(key.text()));
          synthesizeDeduped(backend, effective, key);
        });
  }

  /**
   * Cancels prefetches queued for the current dialogue node (dialogue closed or NPC changed) by
   * advancing the prefetch epoch, so queued tasks drop instead of spending on a branch the player
   * has left. A prefetch already mid-flight finishes and its bytes stay cached.
   */
  public void cancelPrefetch() {
    prefetchEpoch.incrementAndGet();
  }

  /**
   * Builds the cache key for a resolved request: the active backend id, the voice key folded with
   * any backend-specific render variant (selectable cloud model/voice, profile, language), the
   * downgraded emotion, and the text. Shared by {@link #speak} and {@link #prefetch} so a
   * prefetched line and the real line resolve to the exact same key.
   */
  private CacheKey keyFor(SynthesisBackend backend, SynthesisRequest effective) {
    String variant = backend.cacheVariant(effective);
    String voiceKey =
        variant.isEmpty() ? effective.voice().key() : effective.voice().key() + "|" + variant;
    return new CacheKey(backend.id(), voiceKey, effective.emotion(), effective.text());
  }

  /** Stops current playback and drops any queued lines for the now-stale dialogue. */
  public void interrupt() {
    epoch.incrementAndGet();
    output.stop();
  }

  public void close() {
    epoch.incrementAndGet();
    prefetchEpoch.incrementAndGet();
    output.stop();
    shutdown(executor);
    // In production the warm-up and prefetch executors are distinct instances; the test seam reuses
    // the synthesis executor, so only shut a distinct one down once.
    if (warmExecutor != executor) {
      shutdown(warmExecutor);
    }
    if (prefetchExecutor != executor && prefetchExecutor != warmExecutor) {
      shutdown(prefetchExecutor);
    }
    output.close();
  }

  private static void shutdown(Executor exec) {
    if (exec instanceof ExecutorService) {
      ExecutorService es = (ExecutorService) exec;
      es.shutdownNow();
      try {
        // Give an in-flight synth/warm-up a brief window to unwind so a plugin reload does not
        // orphan it.
        es.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void run(
      long mine,
      SynthesisBackend backend,
      SynthesisRequest request,
      CacheKey key,
      boolean applyEcho) {
    if (epoch.get() != mine) {
      return;
    }
    Pcm pcm = lookup(key);
    if (pcm == null) {
      // Both cache tiers missed: synthesize once (de-duped against any concurrent identical synth)
      // and write through to both tiers so the line is free next time, this session and every
      // future one.
      pcm = synthesizeDeduped(backend, request, key);
    }
    if (pcm == null) {
      return;
    }
    // Re-check after the (possibly slow) synth: the line may have been skipped meanwhile, so a
    // cloud
    // response that arrives after the dialogue advanced is dropped rather than played over the top.
    if (epoch.get() != mine) {
      return;
    }
    // Echo is render-only on a fresh buffer; the dry pcm stays in both cache tiers untouched.
    Pcm toPlay = applyEcho ? CaveEcho.apply(pcm) : pcm;
    output.stream(toPlay.getSamples(), toPlay.getSampleRate(), volume.getAsInt());
  }

  /**
   * Memory then disk lookup; a disk hit is promoted into memory. {@code null} when both miss. Every
   * hit notes its tier (memory/disk) and the lookup cost, so a slow disk serve is visible; a miss
   * is left to the synth trace that follows, keeping speculative prefetch misses out of the log.
   */
  private Pcm lookup(CacheKey key) {
    long start = System.nanoTime();
    Pcm pcm = cache.get(key);
    if (pcm != null) {
      log.debug(
          "[TTS cache] hit tier=memory lookupMs={} ({}/{}) \"{}\"",
          elapsedMs(start),
          key.backendId(),
          key.voiceKey(),
          abbreviate(key.text()));
      return pcm;
    }
    // Memory miss: try the persistent on-disk cache (this runs on the pipeline thread, never the
    // game thread). A disk hit is promoted into memory so subsequent replays skip the disk read.
    if (diskCache != null) {
      pcm = diskCache.get(key.backendId(), key.voiceKey(), key.emotion(), key.text());
      if (pcm != null) {
        cache.put(key, pcm);
        log.debug(
            "[TTS cache] hit tier=disk lookupMs={} ({}/{}) \"{}\"",
            elapsedMs(start),
            key.backendId(),
            key.voiceKey(),
            abbreviate(key.text()));
      }
    }
    return pcm;
  }

  /**
   * Synthesizes the line, ensuring at most one backend call per key runs at a time. The first
   * caller for a key registers a pending result, synthesizes, writes through to both cache tiers,
   * and publishes it; a caller that finds a synth already in flight for the same key waits on it
   * instead of issuing a second (billable) backend call. Returns {@code null} on synth failure.
   */
  Pcm synthesizeDeduped(SynthesisBackend backend, SynthesisRequest request, CacheKey key) {
    CompletableFuture<Pcm> own = new CompletableFuture<>();
    CompletableFuture<Pcm> running = inFlight.putIfAbsent(key, own);
    if (running != null) {
      log.debug(
          "[TTS synth] dedup reuse ({}/{}) \"{}\"",
          key.backendId(),
          key.voiceKey(),
          abbreviate(key.text()));
      return await(running);
    }
    Pcm pcm = null;
    try {
      long start = System.nanoTime();
      pcm = backends.synthesizeWith(backend, request);
      // Both cache tiers missed, so this is the real (billable, for cloud) synth: time it so "slow
      // responses" can be quantified, and note the outcome so a silent line is distinguishable from
      // a slow one.
      log.debug(
          "[TTS synth] backend={} ok={} synthMs={} ({}/{}) \"{}\"",
          backend.id(),
          pcm != null,
          elapsedMs(start),
          key.backendId(),
          key.voiceKey(),
          abbreviate(key.text()));
      if (pcm != null) {
        cache.put(key, pcm);
        if (diskCache != null) {
          diskCache.put(key.backendId(), key.voiceKey(), key.emotion(), key.text(), pcm);
        }
      }
    } finally {
      // Publish before deregistering so a waiter that already grabbed this future is never left
      // blocked, and a fresh request right after sees a populated cache rather than re-synthing.
      own.complete(pcm);
      inFlight.remove(key, own);
    }
    return pcm;
  }

  private static Pcm await(CompletableFuture<Pcm> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException e) {
      return null;
    }
  }

  private void submit(Runnable task) {
    try {
      executor.execute(task);
    } catch (RejectedExecutionException ignored) {
      // Queue saturated or shutting down; dropping is fine since newer lines supersede older ones.
    }
  }

  private void submitPrefetch(Runnable task) {
    try {
      prefetchExecutor.execute(task);
    } catch (RejectedExecutionException ignored) {
      // Prefetch backlog full or shutting down; dropping speculative work is always safe.
    }
  }

  /** A factory for daemon threads with the given name, so the JVM can exit without joining them. */
  private static ThreadFactory daemonThreadFactory(String name) {
    return r -> {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    };
  }

  private static ExecutorService buildExecutor(int queueCapacity) {
    return new ThreadPoolExecutor(
        SYNTH_THREADS,
        SYNTH_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        daemonThreadFactory("dialogue-audio"),
        // Drop the oldest queued line under backpressure (newer dialogue supersedes it), but log it
        // so QA can tell whether the queue is actually saturating in practice.
        (r, exec) -> {
          log.debug("[TTS synth] queue saturated; dropping oldest queued line");
          new ThreadPoolExecutor.DiscardOldestPolicy().rejectedExecution(r, exec);
        });
  }

  /**
   * A single-thread executor dedicated to engine warm-up (install/download/model load). Separate
   * from the synthesis executor so a multi-minute engine download cannot stall dialogue playback;
   * its queue is unbounded because warm-up tasks are few (one per backend) and must never be
   * dropped under synthesis backpressure.
   */
  private static ExecutorService buildWarmExecutor() {
    return new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        daemonThreadFactory("dialogue-warm"));
  }

  /**
   * The fixed pool that runs speculative prefetch synths, capped at {@link #PREFETCH_THREADS} so no
   * more than that many prefetch calls are ever in flight at once. Its queue is bounded and the
   * oldest queued prefetch is discarded under backpressure, since speculative work for a node the
   * player may already have left is the safest thing to drop.
   */
  private static ExecutorService buildPrefetchExecutor() {
    return new ThreadPoolExecutor(
        PREFETCH_THREADS,
        PREFETCH_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(PREFETCH_QUEUE_CAPACITY),
        daemonThreadFactory("dialogue-prefetch"),
        new ThreadPoolExecutor.DiscardOldestPolicy());
  }

  private static String abbreviate(String text) {
    return text.length() <= LOG_TEXT_PREVIEW_LENGTH
        ? text
        : text.substring(0, LOG_TEXT_PREVIEW_LENGTH) + "...";
  }

  /** Elapsed wall-clock since {@code startNanos}, in whole milliseconds, for a latency trace. */
  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
