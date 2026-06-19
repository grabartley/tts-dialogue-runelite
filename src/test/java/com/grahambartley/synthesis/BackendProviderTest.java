package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.TTSDialogueConfig.VoiceBackend;
import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.tts.Pcm;
import java.util.EnumSet;
import org.junit.Test;

public class BackendProviderTest {

  /** Config whose backend selection is settable; everything else uses interface defaults. */
  private static final class TestConfig implements TTSDialogueConfig {
    private VoiceBackend backend = VoiceBackend.LOCAL;

    @Override
    public VoiceBackend voiceBackend() {
      return backend;
    }
  }

  /** A backend with a configurable id, availability, and supported-emotion set. */
  private static final class StubBackend implements SynthesisBackend {
    private final String id;
    private boolean available;
    private final EnumSet<Emotion> emotions;
    Emotion lastEmotion;
    int synthCalls;

    StubBackend(String id, boolean available, EnumSet<Emotion> emotions) {
      this.id = id;
      this.available = available;
      this.emotions = emotions;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public EnumSet<Emotion> supportedEmotions() {
      return emotions;
    }

    @Override
    public Pcm synthesize(SynthesisRequest request) {
      synthCalls++;
      lastEmotion = request.emotion();
      return new Pcm(new float[] {0f}, 24_000);
    }
  }

  private static SynthesisRequest req(Emotion emotion) {
    return new SynthesisRequest("hi", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), emotion);
  }

  @Test
  public void defaultLocalConfigSelectsKokoro() {
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    BackendProvider provider = new BackendProvider(new TestConfig(), kokoro);
    assertEquals(BackendProvider.LOCAL_KOKORO_ID, provider.active().id());
  }

  @Test
  public void unavailableSelectedBackendFallsBackToKokoro() {
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    StubBackend azure = new StubBackend("cloud-azure", false, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, azure);

    assertEquals(BackendProvider.LOCAL_KOKORO_ID, provider.active().id());
  }

  @Test
  public void availableSelectedBackendIsChosen() {
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    StubBackend azure = new StubBackend("cloud-azure", true, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, azure);

    assertEquals("cloud-azure", provider.active().id());
  }

  @Test
  public void runtimeSwitchTakesEffectWithoutRecreatingProvider() {
    TestConfig config = new TestConfig();
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    StubBackend azure = new StubBackend("cloud-azure", true, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, azure);

    assertEquals(BackendProvider.LOCAL_KOKORO_ID, provider.active().id());
    config.backend = VoiceBackend.CLOUD;
    assertEquals(
        "switching config selects the new backend live", "cloud-azure", provider.active().id());
  }

  @Test
  public void unsupportedEmotionDowngradesToNeutralBeforeSynthesis() {
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    BackendProvider provider = new BackendProvider(new TestConfig(), kokoro);

    provider.synthesize(req(Emotion.ANGRY));

    assertEquals("backend never sees an unsupported emotion", Emotion.NEUTRAL, kokoro.lastEmotion);
    assertEquals(1, kokoro.synthCalls);
  }

  @Test
  public void supportedEmotionIsPassedThroughUnchanged() {
    StubBackend azure = new StubBackend("cloud-azure", true, EnumSet.allOf(Emotion.class));
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    BackendProvider provider = new BackendProvider(config, kokoro, azure);

    provider.synthesize(req(Emotion.ANGRY));

    assertEquals("a supported emotion is preserved", Emotion.ANGRY, azure.lastEmotion);
  }

  @Test
  public void availabilityNoticeFiresOnceOnFallback() {
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    StubBackend azure = new StubBackend("cloud-azure", false, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, azure);

    int[] notices = {0};
    provider.setAvailabilityNotice(msg -> notices[0]++);

    provider.active();
    provider.active();

    assertEquals("fallback notice should fire once per unavailable backend", 1, notices[0]);
  }

  @Test
  public void availabilityNoticeFiresOncePerBackendWhenCyclingUnavailableBackends() {
    TestConfig config = new TestConfig();
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    StubBackend azure = new StubBackend("cloud-azure", false, EnumSet.allOf(Emotion.class));
    StubBackend zonos = new StubBackend("local-zonos", false, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, azure, zonos);

    int[] notices = {0};
    provider.setAvailabilityNotice(msg -> notices[0]++);

    // Cycle CLOUD -> LOCAL_GPU -> CLOUD. Each distinct unavailable backend should warn exactly
    // once,
    // and returning to a previously warned backend must not re-fire.
    config.backend = VoiceBackend.CLOUD;
    provider.active();
    config.backend = VoiceBackend.LOCAL_GPU;
    provider.active();
    config.backend = VoiceBackend.CLOUD;
    provider.active();

    assertEquals("each unavailable backend warns at most once per session", 2, notices[0]);
  }

  @Test
  public void selectedAndFallbackBothUnavailableStillReturnsKokoroWithoutThrowing() {
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    // The bundled fallback itself failed to load.
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, false, EnumSet.of(Emotion.NEUTRAL));
    StubBackend azure = new StubBackend("cloud-azure", false, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, azure);

    // active() must not throw and must return the local fallback even though it is unavailable; the
    // one-time silent-failure warning is logged as a side effect. Calling twice proves idempotence.
    assertEquals(BackendProvider.LOCAL_KOKORO_ID, provider.active().id());
    assertEquals(BackendProvider.LOCAL_KOKORO_ID, provider.active().id());
  }

  @Test
  public void missingLocalKokoroBackendIsRejected() {
    StubBackend azure = new StubBackend("cloud-azure", true, EnumSet.allOf(Emotion.class));
    boolean threw = false;
    try {
      new BackendProvider(new TestConfig(), azure);
    } catch (IllegalArgumentException e) {
      threw = true;
    }
    assertTrue("a provider must always have the local-kokoro fallback", threw);
  }
}
