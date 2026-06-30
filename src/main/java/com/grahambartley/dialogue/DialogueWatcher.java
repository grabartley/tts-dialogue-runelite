package com.grahambartley.dialogue;

import com.grahambartley.synthesis.SynthesisDispatcher;
import com.grahambartley.tts.DialogueAudioService;
import com.grahambartley.voice.VoiceManager;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

/**
 * Scans the dialogue widgets each game tick and drives the speak/prefetch/interrupt flow: speaks a
 * new NPC or player line once (deduped against the last spoken text), warms the visible options,
 * and edge-triggers the close interrupt so audio is cut only on the open-&gt;closed transition (not
 * on every idle tick, which would truncate public-chat clips played while walking around). Reads
 * the client only on the game thread.
 */
public final class DialogueWatcher {

  private final Client client;
  private final DialogueTextCleaner textCleaner;
  private final DialogueWidgetReader widgetReader;
  private final SynthesisDispatcher dispatcher;
  private final DialoguePrefetchCoordinator prefetchCoordinator;
  private final DialoguePrefetcher prefetcher;
  private final DialogueAudioService audioService;

  private String lastSpoken = "";
  private boolean wasDialogueOpen;

  public DialogueWatcher(
      Client client,
      DialogueTextCleaner textCleaner,
      DialogueWidgetReader widgetReader,
      SynthesisDispatcher dispatcher,
      DialoguePrefetchCoordinator prefetchCoordinator,
      DialoguePrefetcher prefetcher,
      DialogueAudioService audioService) {
    this.client = client;
    this.textCleaner = textCleaner;
    this.widgetReader = widgetReader;
    this.dispatcher = dispatcher;
    this.prefetchCoordinator = prefetchCoordinator;
    this.prefetcher = prefetcher;
    this.audioService = audioService;
  }

  public void tick() {
    Widget npcDialogue = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
    if (npcDialogue != null && !npcDialogue.isHidden()) {
      String text = npcDialogue.getText();
      if (text != null && !text.isEmpty() && !text.equals(lastSpoken)) {
        lastSpoken = text;
        String cleaned = textCleaner.clean(text);
        String npcName = widgetReader.currentNpcName();
        int headAnimationId = widgetReader.headAnimationId(InterfaceID.ChatLeft.HEAD);
        dispatcher.speakDialogue(cleaned, VoiceManager.SPEAKER_NPC, npcName, headAnimationId);
      }
    }

    Widget playerDialogue = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
    if (playerDialogue != null && !playerDialogue.isHidden()) {
      String text = playerDialogue.getText();
      if (text != null && !text.isEmpty() && !text.equals(lastSpoken)) {
        lastSpoken = text;
        String cleaned = textCleaner.clean(text);
        int headAnimationId = widgetReader.headAnimationId(InterfaceID.ChatRight.HEAD);
        // No NPC name needed for player lines.
        dispatcher.speakDialogue(cleaned, VoiceManager.SPEAKER_PLAYER, null, headAnimationId);
      }
    }

    Widget options = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
    boolean optionsVisible = options != null && !options.isHidden();
    if (optionsVisible) {
      prefetchCoordinator.prefetchOptions(options);
    }

    boolean dialogueOpen =
        (npcDialogue != null && !npcDialogue.isHidden())
            || (playerDialogue != null && !playerDialogue.isHidden());
    if (shouldInterruptOnClose(dialogueOpen, wasDialogueOpen)) {
      audioService.interrupt();
      lastSpoken = "";
    }
    wasDialogueOpen = dialogueOpen;

    // Reset prefetch only when the dialogue is fully gone (no text and no option list), so the
    // session cap and queued warming survive the option-select screen instead of being cancelled
    // and re-cancelled every tick while the player is choosing.
    boolean fullyClosed =
        (npcDialogue == null || npcDialogue.isHidden())
            && (playerDialogue == null || playerDialogue.isHidden())
            && !optionsVisible;
    if (fullyClosed) {
      prefetcher.reset();
    }
  }

  /**
   * Pure decision for the close interrupt: cut audio only on the open-&gt;closed transition, so the
   * idle ticks while the player walks around (no dialogue open) never interrupt a playing
   * public-chat clip. Factored out so it is unit-testable without a live client.
   */
  static boolean shouldInterruptOnClose(boolean dialogueOpen, boolean wasDialogueOpen) {
    return wasDialogueOpen && !dialogueOpen;
  }
}
