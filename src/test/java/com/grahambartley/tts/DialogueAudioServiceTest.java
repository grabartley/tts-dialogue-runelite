package com.grahambartley.tts;

import static org.junit.Assert.assertEquals;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

public class DialogueAudioServiceTest {

  /** Records synth requests and hands back canned PCM so cache behavior is observable. */
  private static final class FakeBackend implements SynthesisBackend {
    final List<String> requests = new ArrayList<>();
    private final String id;
    private final EnumSet<Emotion> supported;

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

    @Override
    public void stream(float[] samples, int sampleRate, int volumePercent) {
      streamCalls++;
      lastVolume = volumePercent;
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
    return new DialogueAudioService(provider, output, executor, cacheSize, () -> volume);
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
    FakeBackend cloud = new FakeBackend("cloud-azure", EnumSet.allOf(Emotion.class));
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
}
