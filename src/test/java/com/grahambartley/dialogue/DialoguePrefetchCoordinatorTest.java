package com.grahambartley.dialogue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.synthesis.BackendProvider;
import com.grahambartley.synthesis.ProfanityFilter;
import com.grahambartley.synthesis.SynthesisBackend;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import com.grahambartley.voice.ProfileResolver;
import com.grahambartley.voice.VoiceManager;
import java.util.List;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Warms the cache for the visible dialogue options: each non-header, non-blank option is built into
 * the same player request the dispatcher would produce and handed to the prefetcher. Gated by the
 * prefetch toggle and backend availability.
 */
public class DialoguePrefetchCoordinatorTest {

  private final VoiceManager voiceManager = mock(VoiceManager.class);
  private final ProfileResolver profileResolver = mock(ProfileResolver.class);
  private final DialoguePrefetcher prefetcher = mock(DialoguePrefetcher.class);
  private final BackendProvider backendProvider = mock(BackendProvider.class);
  private final SynthesisBackend backend = mock(SynthesisBackend.class);
  private final TTSDialogueConfig config = mock(TTSDialogueConfig.class);

  private final DialoguePrefetchCoordinator coordinator =
      new DialoguePrefetchCoordinator(
          voiceManager,
          profileResolver,
          new DialogueTextCleaner(new ProfanityFilter()),
          prefetcher,
          backendProvider,
          config);

  @Before
  public void setUp() {
    when(backendProvider.active()).thenReturn(backend);
  }

  @Test
  public void doesNothingWhenPrefetchDisabled() {
    when(config.prefetch()).thenReturn(false);
    coordinator.prefetchOptions(mock(Widget.class));
    verify(prefetcher, never()).offer(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  public void doesNothingWhenBackendUnavailable() {
    when(config.prefetch()).thenReturn(true);
    when(backend.isAvailable()).thenReturn(false);
    coordinator.prefetchOptions(mock(Widget.class));
    verify(prefetcher, never()).offer(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  public void offersOnlyRealOptionsSkippingHeaderBlankAndNull() {
    when(config.prefetch()).thenReturn(true);
    when(backend.isAvailable()).thenReturn(true);
    when(voiceManager.resolveVoice(VoiceManager.SPEAKER_PLAYER, null))
        .thenReturn(mock(VoiceSpec.class));

    Widget[] children = {
      option("Select an Option"), option("Yes, I'll help."), null, option(""), option("No thanks.")
    };
    Widget options = mock(Widget.class);
    when(options.getDynamicChildren()).thenReturn(children);

    coordinator.prefetchOptions(options);

    ArgumentCaptor<List<SynthesisRequest>> captor = ArgumentCaptor.forClass(List.class);
    verify(prefetcher).offer(captor.capture());
    List<SynthesisRequest> offered = captor.getValue();
    assertEquals(2, offered.size());
    assertEquals("Yes, I'll help.", offered.get(0).text());
    assertEquals("No thanks.", offered.get(1).text());
  }

  private static Widget option(String text) {
    Widget w = mock(Widget.class);
    when(w.getText()).thenReturn(text);
    return w;
  }
}
