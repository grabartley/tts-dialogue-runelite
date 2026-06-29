package com.grahambartley.tts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.TTSDialogueConfig.VoiceBackend;
import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.synthesis.BackendProvider;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.SynthesisBackend;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DialogueAudioServiceTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  /** Records synth requests and hands back canned PCM so cache behavior is observable. */
  private static final class FakeBackend implements SynthesisBackend {
    final List<String> requests = new ArrayList<>();
    private final String id;
    private final EnumSet<Emotion> supported;
    volatile boolean throttled;

    FakeBackend(EnumSet<Emotion> supported) {
      // Use the fallback id so a default-LOCAL config resolves straight to this backend.
      this(BackendProvider.LOCAL_KOKORO_ID, supported);
    }

    FakeBackend(String id, EnumSet<Emotion> supported) {
      this.id = id;
      this.supported = supported;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isThrottled() {
      return throttled;
    }

    @Override
    public EnumSet<Emotion> supportedEmotions() {
      return supported;
    }

    @Override
    public Pcm synthesize(SynthesisRequest request) {
      requests.add(request.voice().key() + "|" + request.emotion() + "|" + request.text());
      return new Pcm(new float[] {0.1f, -0.1f}, 24_000);
    }
  }

  /** Records playback and interruption so the pipeline's decisions are observable. */
  private static final class FakeOutput implements AudioOutput {
    int streamCalls;
    int stopCalls;
    int lastVolume = -1;
    float[] lastSamples;

    @Override
    public void stream(float[] samples, int sampleRate, int volumePercent) {
      streamCalls++;
      lastVolume = volumePercent;
      lastSamples = samples;
    }

    @Override
    public void stop() {
      stopCalls++;
    }

    @Override
    public void close() {}
  }

  /** Executor that defers tasks until explicitly drained, to simulate a real queue. */
  private static final class DeferredExecutor implements Executor {
    final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    void runAll() {
      // Copy because running a task may enqueue more.
      List<Runnable> snapshot = new ArrayList<>(tasks);
      tasks.clear();
      for (Runnable r : snapshot) {
        r.run();
      }
    }
  }

  /**
   * Config whose only meaningful answer is the backend selection; everything else uses defaults.
   */
  private static final class TestConfig implements TTSDialogueConfig {
    private VoiceBackend backend = VoiceBackend.LOCAL;

    @Override
    public VoiceBackend voiceBackend() {
      return backend;
    }
  }

  private static BackendProvider provider(SynthesisBackend backend) {
    return new BackendProvider(new TestConfig(), backend);
  }

  private static DialogueAudioService service(
      BackendProvider provider, AudioOutput output, Executor executor, int cacheSize, int volume) {
    // The existing in-memory cache tests do not exercise the disk layer; pass null for it.
    return new DialogueAudioService(provider, output, null, executor, cacheSize, () -> volume);
  }

  private static SynthesisRequest req(String text, NPCRace race, NPCGender gender) {
    return new SynthesisRequest(text, VoiceSpec.npc(race, gender), Emotion.NEUTRAL);
  }

  private static SynthesisRequest req(String text, NPCRace race, NPCGender gender, Emotion e) {
    return new SynthesisRequest(text, VoiceSpec.npc(race, gender), e);
  }

  @Test
  public void repeatedLineIsServedFromCacheWithoutResynth() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.speak(req("Hello adventurer", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();
    svc.speak(req("Hello adventurer", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals("second identical line should hit the cache", 1, backend.requests.size());
    assertEquals("both lines should still play", 2, output.streamCalls);
  }

  @Test
  public void sameTextDifferentVoiceIsSynthesizedSeparately() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.speak(req("Greetings", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();
    svc.speak(req("Greetings", NPCRace.ELF, NPCGender.FEMALE));
    executor.runAll();

    assertEquals("different voices are distinct cache keys", 2, backend.requests.size());
  }

  @Test
  public void sameTextAndVoiceDifferentEmotionIsSynthesizedSeparately() {
    // A backend that supports two emotions so neither downgrades to NEUTRAL.
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL, Emotion.ANGRY));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.speak(req("Halt", NPCRace.HUMAN, NPCGender.MALE, Emotion.NEUTRAL));
    executor.runAll();
    svc.speak(req("Halt", NPCRace.HUMAN, NPCGender.MALE, Emotion.ANGRY));
    executor.runAll();

    assertEquals("emotion is part of the cache key, no collision", 2, backend.requests.size());
    assertEquals("npc:HUMAN:MALE|NEUTRAL|Halt", backend.requests.get(0));
    assertEquals("npc:HUMAN:MALE|ANGRY|Halt", backend.requests.get(1));
  }

  @Test
  public void staleLineIsDroppedWhenSupersededBeforeItRuns() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    // Two lines enqueued before either runs: advancing dialogue supersedes the first.
    svc.speak(req("First line", NPCRace.HUMAN, NPCGender.MALE));
    svc.speak(req("Second line", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals("stale first line should never synthesize", 1, backend.requests.size());
    assertTrue(backend.requests.get(0).endsWith("|Second line"));
    assertEquals("only the live line should play", 1, output.streamCalls);
  }

  @Test
  public void everyNewLineStopsCurrentPlayback() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.speak(req("a", NPCRace.HUMAN, NPCGender.MALE));
    svc.speak(req("b", NPCRace.HUMAN, NPCGender.MALE));

    assertEquals("each speak interrupts whatever is playing", 2, output.stopCalls);
  }

  @Test
  public void interruptStopsPlaybackAndDropsQueuedLine() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.speak(req("Queued line", NPCRace.HUMAN, NPCGender.MALE));
    svc.interrupt();
    executor.runAll();

    assertTrue("interrupt should stop current audio", output.stopCalls >= 1);
    assertEquals("queued stale line should not synthesize", 0, backend.requests.size());
    assertEquals("queued stale line should not play", 0, output.streamCalls);
  }

  @Test
  public void failedSynthIsNotCachedOrPlayed() {
    SynthesisBackend failing =
        new SynthesisBackend() {
          @Override
          public String id() {
            return BackendProvider.LOCAL_KOKORO_ID;
          }

          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public EnumSet<Emotion> supportedEmotions() {
            return EnumSet.of(Emotion.NEUTRAL);
          }

          @Override
          public Pcm synthesize(SynthesisRequest request) {
            return null;
          }
        };
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(failing), output, executor, 8, 100);

    svc.speak(req("anything", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals("null synth result should not play", 0, output.streamCalls);
  }

  @Test
  public void emptyTextIsIgnored() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.speak(req("", NPCRace.HUMAN, NPCGender.MALE));
    svc.speak(null);
    executor.runAll();

    assertEquals(0, backend.requests.size());
    assertEquals(0, output.streamCalls);
    assertEquals("empty lines do not even interrupt", 0, output.stopCalls);
  }

  @Test
  public void currentVolumeIsForwardedToPlayback() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 42);

    svc.speak(req("line", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals(42, output.lastVolume);
  }

  @Test
  public void switchingBackendInvalidatesCacheAndResynthesizesOnTheNewBackend() {
    // Two backends, both available: the local fallback and a cloud backend. The backend id is part
    // of the cache key, so speaking the same line after switching the config backend must re-synth
    // on the new backend rather than replaying the cached local PCM.
    FakeBackend local = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeBackend cloud = new FakeBackend("cloud-openrouter", EnumSet.allOf(Emotion.class));
    TestConfig config = new TestConfig();
    BackendProvider provider = new BackendProvider(config, local, cloud);
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider, output, executor, 8, 100);

    SynthesisRequest line = req("Well met", NPCRace.HUMAN, NPCGender.MALE, Emotion.NEUTRAL);

    config.backend = VoiceBackend.LOCAL;
    svc.speak(line);
    executor.runAll();

    config.backend = VoiceBackend.CLOUD;
    svc.speak(line);
    executor.runAll();

    assertEquals("local backend synthesizes the first line", 1, local.requests.size());
    assertEquals(
        "switching to cloud must re-synth, not serve the cached local PCM",
        1,
        cloud.requests.size());
    assertEquals("both lines still play", 2, output.streamCalls);
  }

  @Test
  public void unsupportedEmotionDowngradesToNeutralAndSharesCacheEntry() {
    // Kokoro-style backend: NEUTRAL only. An ANGRY line downgrades and collides with the NEUTRAL
    // one, proving the downgrade happens before the cache key is built.
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.speak(req("Be gone", NPCRace.HUMAN, NPCGender.MALE, Emotion.NEUTRAL));
    executor.runAll();
    svc.speak(req("Be gone", NPCRace.HUMAN, NPCGender.MALE, Emotion.ANGRY));
    executor.runAll();

    assertEquals("downgraded emotion reuses the neutral cache entry", 1, backend.requests.size());
    assertTrue(backend.requests.get(0).contains("|NEUTRAL|"));
    assertEquals("both lines still play", 2, output.streamCalls);
  }

  @Test
  public void lineSynthesizedInOneSessionIsServedFromDiskInTheNext() {
    Path cacheDir = tmp.getRoot().toPath().resolve("cache");
    SynthesisRequest line = req("Welcome to Lumbridge", NPCRace.HUMAN, NPCGender.FEMALE);

    // Session 1: fresh service with a fresh in-memory cache, real disk cache. Synthesizes once.
    FakeBackend backend1 = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    DeferredExecutor exec1 = new DeferredExecutor();
    DialogueAudioService session1 =
        new DialogueAudioService(
            provider(backend1),
            new FakeOutput(),
            new DiskAudioCache(cacheDir),
            exec1,
            8,
            () -> 100);
    session1.speak(line);
    exec1.runAll();
    assertEquals("first session synthesizes the line", 1, backend1.requests.size());

    // Session 2: brand new service and a brand new in-memory cache, same disk dir. The in-memory
    // tier is empty, so without disk this would re-synthesize.
    FakeBackend backend2 = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output2 = new FakeOutput();
    DeferredExecutor exec2 = new DeferredExecutor();
    DialogueAudioService session2 =
        new DialogueAudioService(
            provider(backend2), output2, new DiskAudioCache(cacheDir), exec2, 8, () -> 100);
    session2.speak(line);
    exec2.runAll();

    assertEquals(
        "second session must be served from disk, not re-synthesized", 0, backend2.requests.size());
    assertEquals("the line still plays in the new session", 1, output2.streamCalls);
  }

  @Test
  public void concurrentIdenticalSynthsIssueExactlyOneBackendCall() throws Exception {
    // Two tasks reach the synth step for the same key at once (a real cloud call is slow). The
    // first must be the only one billed; the second waits on and reuses its result.
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    Pcm canned = new Pcm(new float[] {0.2f, -0.2f}, 24_000);
    SynthesisBackend blocking =
        new SynthesisBackend() {
          @Override
          public String id() {
            return BackendProvider.LOCAL_KOKORO_ID;
          }

          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public EnumSet<Emotion> supportedEmotions() {
            return EnumSet.of(Emotion.NEUTRAL);
          }

          @Override
          public Pcm synthesize(SynthesisRequest request) {
            calls.incrementAndGet();
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return canned;
          }
        };
    DialogueAudioService svc =
        service(provider(blocking), new FakeOutput(), new DeferredExecutor(), 8, 100);
    DialogueAudioService.CacheKey key =
        new DialogueAudioService.CacheKey(
            BackendProvider.LOCAL_KOKORO_ID, "npc:HUMAN:MALE", Emotion.NEUTRAL, "Echo");
    SynthesisRequest request = req("Echo", NPCRace.HUMAN, NPCGender.MALE);

    AtomicReference<Pcm> first = new AtomicReference<>();
    AtomicReference<Pcm> second = new AtomicReference<>();
    Thread owner = new Thread(() -> first.set(svc.synthesizeDeduped(blocking, request, key)));
    owner.start();
    assertTrue("owner reached the backend", entered.await(2, TimeUnit.SECONDS));
    Thread waiter = new Thread(() -> second.set(svc.synthesizeDeduped(blocking, request, key)));
    waiter.start();
    // Wait until the waiter is parked inside the in-flight future's get(), so releasing the owner
    // cannot race ahead and let the waiter register itself as a second owner.
    while (waiter.getState() != Thread.State.WAITING) {
      Thread.onSpinWait();
    }
    release.countDown();
    owner.join(2_000);
    waiter.join(2_000);

    assertEquals(
        "two simultaneous identical requests issue exactly one backend call", 1, calls.get());
    assertNotNull("the owner produced audio", first.get());
    assertSame("the waiter reuses the owner's audio", first.get(), second.get());
  }

  @Test
  public void lateResponseIsDroppedWhenEpochAdvancesDuringSynth() {
    // Simulates a slow cloud response that lands after the player skipped ahead: the epoch bumps
    // mid-synth, so the now-stale audio must never play even though synthesis succeeded.
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    final DialogueAudioService[] holder = new DialogueAudioService[1];
    SynthesisBackend slow =
        new SynthesisBackend() {
          @Override
          public String id() {
            return BackendProvider.LOCAL_KOKORO_ID;
          }

          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public EnumSet<Emotion> supportedEmotions() {
            return EnumSet.of(Emotion.NEUTRAL);
          }

          @Override
          public Pcm synthesize(SynthesisRequest request) {
            holder[0].interrupt();
            return new Pcm(new float[] {0.1f, -0.1f}, 24_000);
          }
        };
    DialogueAudioService svc = service(provider(slow), output, executor, 8, 100);
    holder[0] = svc;

    svc.speak(req("Stale cloud line", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals(
        "a response that lands after the epoch advanced must not play", 0, output.streamCalls);
  }

  @Test
  public void prefetchWarmsCacheWithoutPlayingOrInterrupting() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.prefetch(req("Yes, I'll help.", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals("prefetch synthesizes the line", 1, backend.requests.size());
    assertEquals("prefetch never plays audio", 0, output.streamCalls);
    assertEquals("prefetch never interrupts playback", 0, output.stopCalls);

    // The real line now arrives: it must come from the warmed cache, not a second synth.
    svc.speak(req("Yes, I'll help.", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals("the spoken line is served from the prefetched cache", 1, backend.requests.size());
    assertEquals("the spoken line plays once", 1, output.streamCalls);
  }

  @Test
  public void prefetchOfAnAlreadyCachedLineIsANoOp() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), new FakeOutput(), executor, 8, 100);

    svc.speak(req("Already heard", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();
    svc.prefetch(req("Already heard", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals("prefetch never re-synthesizes a cached line", 1, backend.requests.size());
  }

  @Test
  public void prefetchSkipsWhenTheBackendIsThrottled() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    backend.throttled = true;
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), new FakeOutput(), executor, 8, 100);

    svc.prefetch(req("Don't pile on the 429", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals("a rate-limited backend is never prefetched", 0, backend.requests.size());
  }

  @Test
  public void cancelPrefetchDropsStillQueuedPrefetches() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), new FakeOutput(), executor, 8, 100);

    // Queued but not yet run: leaving the node cancels it before it can spend.
    svc.prefetch(req("Branch the player left", NPCRace.HUMAN, NPCGender.MALE));
    svc.cancelPrefetch();
    executor.runAll();

    assertEquals("a cancelled prefetch never reaches the backend", 0, backend.requests.size());
  }

  @Test
  public void prefetchedLineIsNotRebilledWhenSpokenAcrossTiers() {
    // Prefetch writes through to disk too, so even a fresh in-memory tier serves the spoken line.
    Path cacheDir = tmp.getRoot().toPath().resolve("cache");
    FakeBackend warm = new FakeBackend("cloud-openrouter", EnumSet.allOf(Emotion.class));
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc =
        new DialogueAudioService(
            new BackendProvider(config, new FakeBackend(EnumSet.of(Emotion.NEUTRAL)), warm),
            new FakeOutput(),
            new DiskAudioCache(cacheDir),
            executor,
            8,
            () -> 100);

    svc.prefetch(req("Tell me about the quest.", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();
    svc.speak(req("Tell me about the quest.", NPCRace.HUMAN, NPCGender.MALE));
    executor.runAll();

    assertEquals(
        "prefetch then speak bills the cloud backend exactly once", 1, warm.requests.size());
  }

  @Test
  public void echoPlaysALongerDifferentBufferThanTheDrySynth() {
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.speak(req("Echoing cave line", NPCRace.HUMAN, NPCGender.MALE), true);
    executor.runAll();

    assertEquals("the echoed line still plays once", 1, output.streamCalls);
    assertTrue("the echoed buffer is longer than the dry synth", output.lastSamples.length > 2);
  }

  @Test
  public void echoIsRenderOnlyAndTheCacheStaysDry() {
    // Speaking with echo, then again without, for the same key must reuse the cached DRY audio:
    // one synth call, and the dry replay streams the original short buffer.
    FakeBackend backend = new FakeBackend(EnumSet.of(Emotion.NEUTRAL));
    FakeOutput output = new FakeOutput();
    DeferredExecutor executor = new DeferredExecutor();
    DialogueAudioService svc = service(provider(backend), output, executor, 8, 100);

    svc.speak(req("Same line", NPCRace.HUMAN, NPCGender.MALE), true);
    executor.runAll();
    int echoedLength = output.lastSamples.length;

    svc.speak(req("Same line", NPCRace.HUMAN, NPCGender.MALE), false);
    executor.runAll();

    assertEquals("echo never triggers a second synth", 1, backend.requests.size());
    assertEquals(
        "the dry replay streams the original two-sample buffer", 2, output.lastSamples.length);
    assertTrue("the echoed buffer was longer than the dry one", echoedLength > 2);
    assertArrayEquals(
        "the cached audio stayed dry", new float[] {0.1f, -0.1f}, output.lastSamples, 0f);
  }

  @Test
  public void crossSessionRepeatCostsTheBackendZeroAdditionalSynthCalls() {
    // The headline cloud-cost guarantee (#37): a repeated (backendId, voiceKey, emotion, text)
    // across sessions must never bill the backend again. A fake cloud backend counts synth calls.
    Path cacheDir = tmp.getRoot().toPath().resolve("cache");
    FakeBackend cloud = new FakeBackend("cloud-openrouter", EnumSet.allOf(Emotion.class));
    SynthesisRequest line =
        req("Have you any quests?", NPCRace.HUMAN, NPCGender.MALE, Emotion.HAPPY);

    // Session 1 on the cloud backend: one paid synth call.
    TestConfig config1 = new TestConfig();
    config1.backend = VoiceBackend.CLOUD;
    DeferredExecutor exec1 = new DeferredExecutor();
    DialogueAudioService session1 =
        new DialogueAudioService(
            new BackendProvider(config1, new FakeBackend(EnumSet.of(Emotion.NEUTRAL)), cloud),
            new FakeOutput(),
            new DiskAudioCache(cacheDir),
            exec1,
            8,
            () -> 100);
    session1.speak(line);
    exec1.runAll();
    int afterFirstSession = cloud.requests.size();
    assertEquals("first hearing of the line costs exactly one API call", 1, afterFirstSession);

    // Session 2: fresh in-memory cache, same disk dir, same line. Must cost ZERO additional calls.
    TestConfig config2 = new TestConfig();
    config2.backend = VoiceBackend.CLOUD;
    DeferredExecutor exec2 = new DeferredExecutor();
    DialogueAudioService session2 =
        new DialogueAudioService(
            new BackendProvider(config2, new FakeBackend(EnumSet.of(Emotion.NEUTRAL)), cloud),
            new FakeOutput(),
            new DiskAudioCache(cacheDir),
            exec2,
            8,
            () -> 100);
    session2.speak(line);
    exec2.runAll();

    assertEquals(
        "a repeated line across sessions must not re-bill the cloud backend",
        afterFirstSession,
        cloud.requests.size());
  }
}
