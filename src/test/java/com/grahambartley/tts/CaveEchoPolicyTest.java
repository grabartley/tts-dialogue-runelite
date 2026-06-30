package com.grahambartley.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.TTSDialogueConfig.VoiceBackend;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Covers the cave-echo seams: {@link CaveEchoPolicy#isUndergroundPoint(WorldPoint)}, the coordinate
 * predicate behind underground detection; {@link CaveEchoPolicy#shouldEchoLine}, the pure gate; and
 * {@link CaveEchoPolicy#isUnderground()}, the client read that feeds the gate.
 */
@RunWith(JUnitParamsRunner.class)
public class CaveEchoPolicyTest {

  private Object[] undergroundPointCases() {
    return new Object[] {
      new Object[] {new WorldPoint(3200, Constants.OVERWORLD_MAX_Y - 1, 0), false},
      new Object[] {new WorldPoint(3200, Constants.OVERWORLD_MAX_Y, 0), true},
      new Object[] {new WorldPoint(3200, 9000, 0), true},
    };
  }

  @Test
  @Parameters(method = "undergroundPointCases")
  public void undergroundPointGatesOnTheOverworldCeiling(WorldPoint point, boolean expected) {
    assertEquals(expected, CaveEchoPolicy.isUndergroundPoint(point));
  }

  @Test
  public void prifddinasIsCorrectedBackToSurfaceByTheMirror() {
    WorldPoint inPrifddinasBand = new WorldPoint(3256, 6055, 0);
    assertTrue(
        "the raw point is in the underground band",
        inPrifddinasBand.getY() >= Constants.OVERWORLD_MAX_Y);
    assertFalse(
        "Prifddinas must read as surface after the mirror correction",
        CaveEchoPolicy.isUndergroundPoint(inPrifddinasBand));
  }

  @Test
  public void playerOwnedHouseIsCarvedOutDespiteSittingInTheBand() {
    for (WorldPoint inPoh :
        new WorldPoint[] {new WorldPoint(1960, 7045, 0), new WorldPoint(1944, 7107, 0)}) {
      assertTrue(
          "the POH point is in a carved-out region",
          CaveEchoPolicy.POH_REGION_IDS.contains(inPoh.getRegionID()));
      assertTrue(
          "the raw point is in the underground band", inPoh.getY() >= Constants.OVERWORLD_MAX_Y);
      assertFalse(
          "the player-owned house must read as surface", CaveEchoPolicy.isUndergroundPoint(inPoh));
    }
  }

  private Object[] shouldEchoLineCases() {
    return new Object[] {
      new Object[] {VoiceBackend.CLOUD, true, true, true},
      new Object[] {VoiceBackend.LOCAL, true, true, false},
      new Object[] {VoiceBackend.CLOUD, false, true, false},
      new Object[] {VoiceBackend.CLOUD, true, false, false},
    };
  }

  @Test
  @Parameters(method = "shouldEchoLineCases")
  public void shouldEchoLineGatesOnCloudBackendToggleAndUnderground(
      VoiceBackend backend, boolean caveEchoEnabled, boolean underground, boolean expected) {
    assertEquals(expected, CaveEchoPolicy.shouldEchoLine(backend, caveEchoEnabled, underground));
  }

  @Test
  public void isUndergroundReadsTheLivePlayerLocation() {
    Client client = mock(Client.class);
    TTSDialogueConfig config = mock(TTSDialogueConfig.class);
    CaveEchoPolicy policy = new CaveEchoPolicy(client, config);

    when(client.getLocalPlayer()).thenReturn(null);
    assertFalse("no local player reads as surface", policy.isUnderground());

    Player player = mock(Player.class);
    when(client.getLocalPlayer()).thenReturn(player);
    when(client.isInInstancedRegion()).thenReturn(false);

    when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 9000, 0));
    assertTrue("a deep cave reads as underground", policy.isUnderground());

    when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
    assertFalse("an overworld tile reads as surface", policy.isUnderground());
  }

  @Test
  public void shouldEchoCombinesBackendToggleAndLocation() {
    Client client = mock(Client.class);
    TTSDialogueConfig config = mock(TTSDialogueConfig.class);
    Player player = mock(Player.class);
    when(client.getLocalPlayer()).thenReturn(player);
    when(client.isInInstancedRegion()).thenReturn(false);
    when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 9000, 0));
    when(config.voiceBackend()).thenReturn(VoiceBackend.CLOUD);
    when(config.cloudCaveEcho()).thenReturn(true);

    assertTrue(new CaveEchoPolicy(client, config).shouldEcho());
  }
}
