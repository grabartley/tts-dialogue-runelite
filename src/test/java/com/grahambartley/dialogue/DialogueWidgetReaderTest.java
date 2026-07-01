package com.grahambartley.dialogue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

/** The client-reading widget accessors behind dialogue detection. */
public class DialogueWidgetReaderTest {

  private final Client client = mock(Client.class);
  private final DialogueWidgetReader reader = new DialogueWidgetReader(client);

  @Test
  public void absentHeadWidgetResolvesToNoExpression() {
    when(client.getWidget(42)).thenReturn(null);
    assertEquals(DialogueWidgetReader.NO_EXPRESSION, reader.headAnimationId(42));
  }

  @Test
  public void presentHeadWidgetReturnsItsAnimationId() {
    Widget head = mock(Widget.class);
    when(client.getWidget(7)).thenReturn(head);
    when(head.getAnimationId()).thenReturn(614);
    assertEquals(614, reader.headAnimationId(7));
  }

  @Test
  public void nameWidgetTextIsTrimmed() {
    Widget nameWidget = mock(Widget.class);
    when(client.getWidget(ComponentID.DIALOG_NPC_NAME)).thenReturn(nameWidget);
    when(nameWidget.isHidden()).thenReturn(false);
    when(nameWidget.getText()).thenReturn(" Hans ");
    assertEquals("Hans", reader.currentNpcName());
  }

  @Test
  public void fallsBackToTheInteractingNpcWhenNameWidgetAbsent() {
    when(client.getWidget(ComponentID.DIALOG_NPC_NAME)).thenReturn(null);
    Player local = mock(Player.class);
    Actor interacting = mock(Actor.class);
    when(client.getLocalPlayer()).thenReturn(local);
    when(local.getInteracting()).thenReturn(interacting);
    when(interacting.getName()).thenReturn("Guard");
    assertEquals("Guard", reader.currentNpcName());
  }

  @Test
  public void lastResortIsUnknownNpc() {
    when(client.getWidget(ComponentID.DIALOG_NPC_NAME)).thenReturn(null);
    when(client.getLocalPlayer()).thenReturn(null);
    assertEquals("Unknown NPC", reader.currentNpcName());
  }
}
