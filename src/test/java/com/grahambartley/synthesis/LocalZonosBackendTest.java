package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.synthesis.engine.EngineInstaller;
import com.grahambartley.synthesis.engine.ExternalEngineClient;
import com.grahambartley.tts.Pcm;
import java.nio.file.Paths;
import java.util.EnumSet;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Behaviour of {@link LocalZonosBackend} with a mocked installer and transport (no real engine, no
 * GPU): full emotion set, GPU-gated availability driven by the engine handshake, the
 * dev/empty-manifest path, lazy warm-up, and that synthesis routes the per-emotion 8-dim vector
 * through the shared {@link ExternalEngineClient}.
 */
public class LocalZonosBackendTest {

  private static SynthesisRequest request(Emotion emotion) {
    return new SynthesisRequest("hi", VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE), emotion);
  }

  private static EngineInstaller installerReturning(EngineInstaller.Installed installed) {
    EngineInstaller installer = mock(EngineInstaller.class);
    when(installer.install()).thenReturn(installed);
    return installer;
  }

  private static EngineInstaller.Installed fakeInstall() {
    return new EngineInstaller.Installed(Paths.get("/fake/zonos-engine"), "zonos", "1.0.0");
  }

  @Test
  public void supportsFullEmotionSet() {
    LocalZonosBackend backend =
        new LocalZonosBackend(mock(EngineInstaller.class), l -> mock(ExternalEngineClient.class));
    assertTrue(
        backend
            .supportedEmotions()
            .containsAll(
                EnumSet.of(
                    Emotion.NEUTRAL, Emotion.HAPPY, Emotion.SAD, Emotion.ANGRY, Emotion.SCARED)));
    assertEquals("local-zonos", backend.id());
  }

  @Test
  public void unavailableBeforeWarmUp() {
    LocalZonosBackend backend =
        new LocalZonosBackend(mock(EngineInstaller.class), l -> mock(ExternalEngineClient.class));
    // GPU backends must not be reported available until the engine has spawned and reported a GPU.
    assertFalse(backend.isAvailable());
  }

  @Test
  public void devOrEmptyManifestKeepsBackendUnavailable() {
    // install() returning null mirrors the committed dev/empty Zonos manifest (no engine
    // published).
    LocalZonosBackend backend =
        new LocalZonosBackend(installerReturning(null), l -> mock(ExternalEngineClient.class));
    backend.warmUp();
    assertFalse("dev/empty manifest must degrade to unavailable", backend.isAvailable());
    assertNull(
        "synthesis must return null, not crash, when unavailable",
        backend.synthesize(request(Emotion.HAPPY)));
  }

  @Test
  public void healthyEngineWithGpuIsAvailable() {
    ExternalEngineClient client = mock(ExternalEngineClient.class);
    when(client.handshake())
        .thenReturn(new ExternalEngineClient.Health(true, true, "cuda:0 RTX 4090"));
    when(client.isHealthy()).thenReturn(true);

    LocalZonosBackend backend =
        new LocalZonosBackend(installerReturning(fakeInstall()), l -> client);
    backend.warmUp();

    assertTrue("healthy engine reporting a GPU must be available", backend.isAvailable());
  }

  @Test
  public void healthyEngineWithoutGpuIsUnavailable() {
    ExternalEngineClient client = mock(ExternalEngineClient.class);
    when(client.handshake())
        .thenReturn(new ExternalEngineClient.Health(true, false, "no CUDA device"));

    LocalZonosBackend backend =
        new LocalZonosBackend(installerReturning(fakeInstall()), l -> client);
    backend.warmUp();

    assertFalse(
        "healthy engine with no GPU must be unavailable so the provider falls back",
        backend.isAvailable());
    // The transport that reported no GPU should have been torn down.
    verify(client).stop();
  }

  @Test
  public void failedHandshakeIsUnavailable() {
    ExternalEngineClient client = mock(ExternalEngineClient.class);
    when(client.handshake()).thenReturn(null);

    LocalZonosBackend backend =
        new LocalZonosBackend(installerReturning(fakeInstall()), l -> client);
    backend.warmUp();

    assertFalse(backend.isAvailable());
  }

  @Test
  public void synthesisRoutesPerEmotionVectorThroughTheTransport() {
    ExternalEngineClient client = mock(ExternalEngineClient.class);
    when(client.handshake()).thenReturn(new ExternalEngineClient.Health(true, true, "gpu"));
    when(client.isHealthy()).thenReturn(true);
    Pcm expected = new Pcm(new float[] {0.1f, -0.2f}, 44100);
    when(client.synthesize(any(), any())).thenReturn(expected);

    LocalZonosBackend backend =
        new LocalZonosBackend(installerReturning(fakeInstall()), l -> client);
    backend.warmUp();

    Pcm result = backend.synthesize(request(Emotion.ANGRY));
    assertSame("backend returns the transport's decoded Pcm unchanged", expected, result);

    // The ANGRY 8-dim vector (anger-dominant) is what travels to the engine.
    ArgumentCaptor<float[]> vector = ArgumentCaptor.forClass(float[].class);
    verify(client).synthesize(any(SynthesisRequest.class), vector.capture());
    float[] sent = vector.getValue();
    assertEquals("vector is exactly the 8-dim preset", 8, sent.length);
    int dominant = 0;
    for (int i = 1; i < sent.length; i++) {
      if (sent[i] > sent[dominant]) {
        dominant = i;
      }
    }
    assertEquals("ANGRY must send an anger-dominant vector", ZonosEmotionVectors.ANGER, dominant);
  }

  @Test
  public void closeTearsDownTheEngineAndDropsAvailability() {
    ExternalEngineClient client = mock(ExternalEngineClient.class);
    when(client.handshake()).thenReturn(new ExternalEngineClient.Health(true, true, "gpu"));
    when(client.isHealthy()).thenReturn(true);

    LocalZonosBackend backend =
        new LocalZonosBackend(installerReturning(fakeInstall()), l -> client);
    backend.warmUp();
    assertTrue(backend.isAvailable());

    backend.close();
    verify(client).stop();
    assertFalse("a closed backend must report unavailable", backend.isAvailable());
  }
}
