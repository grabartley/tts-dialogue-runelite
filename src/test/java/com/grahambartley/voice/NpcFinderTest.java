package com.grahambartley.voice;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import org.junit.Test;

/** Tolerant by-name NPC lookup against the world list. */
public class NpcFinderTest {

  @Test
  public void nullClientFindsNothing() {
    assertNull(new NpcFinder(null).findByName("Hans"));
  }

  @Test
  public void nullWorldListFindsNothing() {
    Client client = mock(Client.class);
    when(client.getNpcs()).thenReturn(null);
    assertNull(new NpcFinder(client).findByName("Hans"));
  }

  @Test
  public void blankTargetFindsNothing() {
    Client client = mock(Client.class);
    assertNull(new NpcFinder(client).findByName(""));
  }

  @Test
  public void matchesIgnoringMarkupAndCase() {
    Client client = mock(Client.class);
    NPC hans = npc("Hans");
    NPC bob = npc("Bob");
    when(client.getNpcs()).thenReturn(Arrays.asList(bob, hans));

    assertSame(hans, new NpcFinder(client).findByName("<col=00ff00>hans</col>"));
  }

  @Test
  public void returnsNullWhenNoNameMatches() {
    Client client = mock(Client.class);
    NPC bob = npc("Bob");
    when(client.getNpcs()).thenReturn(Arrays.asList(bob));
    assertNull(new NpcFinder(client).findByName("Hans"));
  }

  private static NPC npc(String name) {
    NPC npc = mock(NPC.class);
    when(npc.getName()).thenReturn(name);
    return npc;
  }
}
