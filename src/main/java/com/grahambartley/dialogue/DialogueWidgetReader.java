package com.grahambartley.dialogue;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

/**
 * Reads the dialogue chat-head animation id and the speaking NPC's name from the client. Only
 * touches the client on the game thread; never throws. Kept separate from the synthesis decision
 * logic so that logic stays unit-testable without a live client.
 */
public final class DialogueWidgetReader {

  /**
   * Sentinel head-animation id meaning "no detectable expression": a missing head widget (sprite /
   * objectbox dialogue) or the one-tick race where the head animation lags the text. Resolves to
   * {@link com.grahambartley.synthesis.Emotion#NEUTRAL}, matching the engine's own {@code -1}.
   */
  static final int NO_EXPRESSION = -1;

  private static final String UNKNOWN_NPC = "Unknown NPC";

  private final Client client;

  public DialogueWidgetReader(Client client) {
    this.client = client;
  }

  /**
   * Reads the chat-head expression animation id from the given dialogue head widget id ({@code
   * InterfaceID.ChatLeft.HEAD} for NPC lines, {@code InterfaceID.ChatRight.HEAD} for player lines).
   * Returns {@link #NO_EXPRESSION} when the head widget is absent (sprite/objectbox dialogues have
   * no head), so the caller resolves NEUTRAL.
   */
  int headAnimationId(int headWidgetId) {
    Widget head = client.getWidget(headWidgetId);
    if (head == null) {
      return NO_EXPRESSION;
    }
    return head.getAnimationId();
  }

  /** Extracts the NPC name from the dialogue name widget, or the current interacting NPC. */
  String currentNpcName() {
    Widget npcNameWidget = client.getWidget(WidgetInfo.DIALOG_NPC_NAME);
    if (npcNameWidget != null && !npcNameWidget.isHidden()) {
      String npcName = npcNameWidget.getText();
      if (npcName != null && !npcName.isEmpty()) {
        return npcName.trim();
      }
    }

    if (client.getLocalPlayer() != null && client.getLocalPlayer().getInteracting() != null) {
      String interactingName = client.getLocalPlayer().getInteracting().getName();
      if (interactingName != null && !interactingName.isEmpty()) {
        return interactingName.trim();
      }
    }

    return UNKNOWN_NPC;
  }
}
