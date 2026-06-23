package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.grahambartley.TTSDialogueConfig.VoiceBackend;
import com.grahambartley.synthesis.BackendProvider;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.SynthesisBackend;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.tts.DialogueAudioService;
import com.grahambartley.tts.Pcm;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.events.ConfigChanged;
import org.junit.Test;

/**
 * Verifies the runtime backend-switch warm-up trigger added for issue #75: a {@link ConfigChanged}
 * for the plugin group and a backend-affecting key re-runs the active backend's off-thread warm-up
 * exactly once, while unrelated groups/keys and a stopped/shutting-down plugin do nothing.
 */
public class TTSDialoguePluginConfigChangeTest {

  /** Pure decision logic: only the plugin group plus a backend-affecting key should warm. */
  @Test
  public void affectsBackendWarmUpRecognisesBackendKeys() {
    assertTrue(TTSDialoguePlugin.affectsBackendWarmUp("ttsDialogue", "voiceBackend"));
    assertTrue(TTSDialoguePlugin.affectsBackendWarmUp("ttsDialogue", "openRouterApiKey"));
    assertTrue(TTSDialoguePlugin.affectsBackendWarmUp("ttsDialogue", "cloudModel"));
  }

  @Test
  public void affectsBackendWarmUpIgnoresUnrelatedGroupKeyOrNulls() {
    // Right group, key that does not affect backend selection/availability.
    assertFalse(TTSDialoguePlugin.affectsBackendWarmUp("ttsDialogue", "volume"));
    // A backend key but a different plugin's config group.
    assertFalse(TTSDialoguePlugin.affectsBackendWarmUp("otherPlugin", "voiceBackend"));
    // Defensive: nulls never throw and never warm.
    assertFalse(TTSDialoguePlugin.affectsBackendWarmUp(null, "voiceBackend"));
    assertFalse(TTSDialoguePlugin.affectsBackendWarmUp("ttsDialogue", null));
  }

  /**
   * A backend-key change in the plugin group drives the real off-thread pipeline end to end: {@code
   * prewarm} -> executor -> {@code warmUpActive} -> the selected (non-Kokoro) backend's {@code
   * warmUp}, exactly once.
   */
  @Test
  public void backendKeyChangeWarmsActiveBackendOnce() throws Exception {
    AtomicInteger warmCalls = new AtomicInteger();
    StubConfig config = new StubConfig();
    config.backend = VoiceBackend.CLOUD; // so warmUpActive targets the non-Kokoro backend
    Harness harness = harness(config, warmCalls);

    harness.plugin.onConfigChanged(configChanged("ttsDialogue", "voiceBackend"));
    harness.audioService.awaitWarm();

    assertEquals(1, warmCalls.get());
  }

  /** Unrelated keys and groups never reach the warm-up path. */
  @Test
  public void unrelatedKeyOrGroupDoesNotWarm() throws Exception {
    AtomicInteger warmCalls = new AtomicInteger();
    StubConfig config = new StubConfig();
    config.backend = VoiceBackend.CLOUD;
    Harness harness = harness(config, warmCalls);

    harness.plugin.onConfigChanged(configChanged("ttsDialogue", "volume"));
    harness.plugin.onConfigChanged(configChanged("otherPlugin", "voiceBackend"));

    assertEquals(0, warmCalls.get());
  }

  /**
   * A config change while the plugin is stopped/shutting down (null collaborators) no-ops safely.
   */
  @Test
  public void configChangeWhileStoppedDoesNotThrow() throws Exception {
    TTSDialoguePlugin plugin = new TTSDialoguePlugin();
    // audioService and backendProvider are null (never started / already shut down).
    plugin.onConfigChanged(configChanged("ttsDialogue", "voiceBackend"));
  }

  // --- helpers -------------------------------------------------------------

  private static ConfigChanged configChanged(String group, String key) {
    ConfigChanged event = new ConfigChanged();
    event.setGroup(group);
    event.setKey(key);
    return event;
  }

  /** Plugin wired with a real DialogueAudioService and BackendProvider over counting stubs. */
  private static Harness harness(StubConfig config, AtomicInteger warmCalls) throws Exception {
    SynthesisBackend kokoro = new StubBackend(BackendProvider.LOCAL_KOKORO_ID, warmCalls);
    SynthesisBackend cloud = new StubBackend("cloud-openrouter", warmCalls);
    BackendProvider provider = new BackendProvider(config, kokoro, cloud);
    DialogueAudioService audioService =
        new DialogueAudioService(provider, null, null, 1, 1, () -> 100);

    TTSDialoguePlugin plugin = new TTSDialoguePlugin();
    setField(plugin, "audioService", audioService);
    setField(plugin, "backendProvider", provider);
    return new Harness(plugin, audioService);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = TTSDialoguePlugin.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  /** Holds the plugin plus the audio service so a test can await the off-thread warm. */
  private static final class Harness {
    final TTSDialoguePlugin plugin;
    final AwaitableAudioService audioService;

    Harness(TTSDialoguePlugin plugin, DialogueAudioService audioService) {
      this.plugin = plugin;
      this.audioService = new AwaitableAudioService(audioService);
    }
  }

  /**
   * Lets a test block until the single-threaded pipeline drains, so the off-thread {@code warmUp}
   * has run before the assertion. Submits a sentinel {@code prewarm} and waits for it to execute.
   */
  private static final class AwaitableAudioService {
    private final DialogueAudioService delegate;

    AwaitableAudioService(DialogueAudioService delegate) {
      this.delegate = delegate;
    }

    void awaitWarm() throws InterruptedException {
      java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
      delegate.prewarm(drained::countDown);
      if (!drained.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new AssertionError("warm-up pipeline did not drain in time");
      }
    }
  }

  private static final class StubConfig implements TTSDialogueConfig {
    private VoiceBackend backend = VoiceBackend.LOCAL;

    @Override
    public VoiceBackend voiceBackend() {
      return backend;
    }
  }

  /** Counts {@code warmUp} calls so the test can assert the active backend was warmed once. */
  private static final class StubBackend implements SynthesisBackend {
    private final String id;
    private final AtomicInteger warmCalls;

    StubBackend(String id, AtomicInteger warmCalls) {
      this.id = id;
      this.warmCalls = warmCalls;
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
      return EnumSet.of(Emotion.NEUTRAL);
    }

    @Override
    public Pcm synthesize(SynthesisRequest request) {
      return new Pcm(new float[] {0f}, 24_000);
    }

    @Override
    public void warmUp() {
      warmCalls.incrementAndGet();
    }
  }
}
