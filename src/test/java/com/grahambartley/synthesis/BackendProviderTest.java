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

  /** A backend that counts {@code warmUp} calls so warm-up routing can be asserted. */
  private static final class WarmCountingBackend implements SynthesisBackend {
    private final String id;
    private final EnumSet<Emotion> emotions;
    int warmCalls;

    WarmCountingBackend(String id, EnumSet<Emotion> emotions) {
      this.id = id;
      this.emotions = emotions;
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
      return emotions;
    }

    @Override
    public Pcm synthesize(SynthesisRequest request) {
      return new Pcm(new float[] {0f}, 24_000);
    }

    @Override
    public void warmUp() {
      warmCalls++;
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
  public void unavailableSelectedBackendIsStillReturnedWithoutSwappingToKokoro() {
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    StubBackend cloud = new StubBackend("cloud-openrouter", false, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, cloud);

    // No fallback: an unavailable Cloud selection still resolves to Cloud (its lines stay silent),
    // never to the local engine.
    assertEquals("cloud-openrouter", provider.active().id());
  }

  @Test
  public void availableSelectedBackendIsChosen() {
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    StubBackend cloud = new StubBackend("cloud-openrouter", true, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, cloud);

    assertEquals("cloud-openrouter", provider.active().id());
  }

  @Test
  public void runtimeSwitchTakesEffectWithoutRecreatingProvider() {
    TestConfig config = new TestConfig();
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    StubBackend cloud = new StubBackend("cloud-openrouter", true, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, cloud);

    assertEquals(BackendProvider.LOCAL_KOKORO_ID, provider.active().id());
    config.backend = VoiceBackend.CLOUD;
    assertEquals(
        "switching config selects the new backend live",
        "cloud-openrouter",
        provider.active().id());
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
    StubBackend cloud = new StubBackend("cloud-openrouter", true, EnumSet.allOf(Emotion.class));
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    BackendProvider provider = new BackendProvider(config, kokoro, cloud);

    provider.synthesize(req(Emotion.ANGRY));

    assertEquals("a supported emotion is preserved", Emotion.ANGRY, cloud.lastEmotion);
  }

  @Test
  public void warmUpActiveWarmsOnlyTheSelectedBackend() {
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    WarmCountingBackend kokoro =
        new WarmCountingBackend(BackendProvider.LOCAL_KOKORO_ID, EnumSet.of(Emotion.NEUTRAL));
    WarmCountingBackend cloud =
        new WarmCountingBackend("cloud-openrouter", EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, cloud);

    provider.warmUpActive();
    assertEquals("Cloud selected warms Cloud", 1, cloud.warmCalls);
    assertEquals("Cloud selected never warms the local engine", 0, kokoro.warmCalls);

    config.backend = VoiceBackend.LOCAL;
    provider.warmUpActive();
    assertEquals("Local selected warms Kokoro", 1, kokoro.warmCalls);
    assertEquals("Local selected makes no further cloud warm", 1, cloud.warmCalls);
  }

  @Test
  public void cloudFullEmotionSetIsNotDowngraded() {
    TestConfig config = new TestConfig();
    config.backend = VoiceBackend.CLOUD;
    StubBackend kokoro =
        new StubBackend(BackendProvider.LOCAL_KOKORO_ID, true, EnumSet.of(Emotion.NEUTRAL));
    StubBackend cloud = new StubBackend("cloud-openrouter", true, EnumSet.allOf(Emotion.class));
    BackendProvider provider = new BackendProvider(config, kokoro, cloud);

    provider.synthesize(req(Emotion.SCARED));

    assertEquals("Cloud supports the full set, so no downgrade", Emotion.SCARED, cloud.lastEmotion);
  }

  @Test
  public void missingLocalKokoroBackendIsRejected() {
    StubBackend cloud = new StubBackend("cloud-openrouter", true, EnumSet.allOf(Emotion.class));
    boolean threw = false;
    try {
      new BackendProvider(new TestConfig(), cloud);
    } catch (IllegalArgumentException e) {
      threw = true;
    }
    assertTrue("a provider must always have the local-kokoro backend registered", threw);
  }
}
