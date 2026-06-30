package com.grahambartley.dialogue;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grahambartley.synthesis.ProfanityFilter;
import com.grahambartley.synthesis.SynthesisDispatcher;
import com.grahambartley.tts.DialogueAudioService;
import com.grahambartley.voice.VoiceManager;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The per-tick dialogue scan: speaks a new NPC or player line once (deduped against the last spoken
 * text) and edge-triggers the close interrupt only on the open-&gt;closed transition, so idle ticks
 * never truncate a playing public-chat clip.
 */
@RunWith(JUnitParamsRunner.class)
public class DialogueWatcherTest {

  private final Client client = mock(Client.class);
  private final DialogueWidgetReader widgetReader = mock(DialogueWidgetReader.class);
  private final SynthesisDispatcher dispatcher = mock(SynthesisDispatcher.class);
  private final DialoguePrefetchCoordinator prefetchCoordinator =
      mock(DialoguePrefetchCoordinator.class);
  private final DialoguePrefetcher prefetcher = mock(DialoguePrefetcher.class);
  private final DialogueAudioService audioService = mock(DialogueAudioService.class);

  private final DialogueWatcher watcher =
      new DialogueWatcher(
          client,
          new DialogueTextCleaner(new ProfanityFilter()),
          widgetReader,
          dispatcher,
          prefetchCoordinator,
          prefetcher,
          audioService);

  @Before
  public void setUp() {
    when(widgetReader.currentNpcName()).thenReturn("Bob");
  }

  private Object[] interruptOnCloseCases() {
    return new Object[] {
      // dialogue just closed -> cut its audio once
      new Object[] {false, true, true},
      // still idle (was closed, still closed) -> never interrupt, so public chat plays on
      new Object[] {false, false, false},
      // dialogue still open -> nothing to interrupt
      new Object[] {true, true, false},
      // dialogue just opened -> nothing to interrupt
      new Object[] {true, false, false},
    };
  }

  @Test
  @Parameters(method = "interruptOnCloseCases")
  public void interruptDecisionFiresOnlyOnTheOpenToClosedTransition(
      boolean dialogueOpen, boolean wasDialogueOpen, boolean expected) {
    assertEquals(expected, DialogueWatcher.shouldInterruptOnClose(dialogueOpen, wasDialogueOpen));
  }

  @Test
  public void newNpcLineIsSpokenOnceThenDeduped() {
    Widget npc = mock(Widget.class);
    when(npc.isHidden()).thenReturn(false);
    when(npc.getText()).thenReturn("Greetings!");
    when(client.getWidget(WidgetInfo.DIALOG_NPC_TEXT)).thenReturn(npc);

    watcher.tick();
    watcher.tick();

    verify(dispatcher, times(1))
        .speakDialogue(eq("Greetings!"), eq(VoiceManager.SPEAKER_NPC), eq("Bob"), anyInt());
  }

  @Test
  public void dialogueClosingInterruptsAudioAndResetsPrefetch() {
    Widget npc = mock(Widget.class);
    when(npc.isHidden()).thenReturn(false);
    when(npc.getText()).thenReturn("Greetings!");
    // Open on the first tick, gone on the second.
    when(client.getWidget(WidgetInfo.DIALOG_NPC_TEXT)).thenReturn(npc, (Widget) null);

    watcher.tick();
    watcher.tick();

    verify(audioService, times(1)).interrupt();
    verify(prefetcher).reset();
  }
}
