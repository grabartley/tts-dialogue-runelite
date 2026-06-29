package com.grahambartley;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.grahambartley.TTSDialogueConfig.VoiceBackend;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/**
 * Covers the pure cave-echo seams: {@link TTSDialoguePlugin#isUndergroundPoint(WorldPoint)}, the
 * coordinate predicate behind underground detection, and {@link TTSDialoguePlugin#shouldEchoLine},
 * the gate that only echoes on the Cloud backend, with the toggle on, while underground. Both are
 * client-free, so they verify the exact decisions the live {@code dispatch} read feeds in.
 */
public class TTSDialoguePluginCaveEchoTest {

  @Test
  public void justBelowTheOverworldCeilingIsSurface() {
    assertFalse(
        TTSDialoguePlugin.isUndergroundPoint(
            new WorldPoint(3200, Constants.OVERWORLD_MAX_Y - 1, 0)));
  }

  @Test
  public void atAndAboveTheOverworldCeilingIsUnderground() {
    assertTrue(
        TTSDialoguePlugin.isUndergroundPoint(new WorldPoint(3200, Constants.OVERWORLD_MAX_Y, 0)));
    assertTrue(
        "a deep cave reads as underground",
        TTSDialoguePlugin.isUndergroundPoint(new WorldPoint(3200, 9000, 0)));
  }

  @Test
  public void prifddinasIsCorrectedBackToSurfaceByTheMirror() {
    // Region 12894 sits in the high-Y band but is a surface elf city; getMirrorPoint normalises it.
    WorldPoint inPrifddinasBand = new WorldPoint(3256, 6055, 0);
    assertTrue(
        "the raw point is in the underground band",
        inPrifddinasBand.getY() >= Constants.OVERWORLD_MAX_Y);
    assertFalse(
        "Prifddinas must read as surface after the mirror correction",
        TTSDialoguePlugin.isUndergroundPoint(inPrifddinasBand));
  }

  @Test
  public void playerOwnedHouseIsCarvedOutDespiteSittingInTheBand() {
    // A POH straddles region boundaries (observed live in regions 7790 and 7791): every house tile
    // lands in the high-Y band but is an enclosed surface instance, not a cave, so it reads
    // surface.
    for (WorldPoint inPoh :
        new WorldPoint[] {new WorldPoint(1960, 7045, 0), new WorldPoint(1944, 7107, 0)}) {
      assertTrue(
          "the POH point is in a carved-out region",
          TTSDialoguePlugin.POH_REGION_IDS.contains(inPoh.getRegionID()));
      assertTrue(
          "the raw point is in the underground band", inPoh.getY() >= Constants.OVERWORLD_MAX_Y);
      assertFalse(
          "the player-owned house must read as surface",
          TTSDialoguePlugin.isUndergroundPoint(inPoh));
    }
  }

  @Test
  public void echoesOnlyForCloudToggleOnUnderground() {
    assertTrue(TTSDialoguePlugin.shouldEchoLine(VoiceBackend.CLOUD, true, true));
  }

  @Test
  public void noEchoOnLocalBackend() {
    assertFalse(TTSDialoguePlugin.shouldEchoLine(VoiceBackend.LOCAL, true, true));
  }

  @Test
  public void noEchoWhenToggleOff() {
    assertFalse(TTSDialoguePlugin.shouldEchoLine(VoiceBackend.CLOUD, false, true));
  }

  @Test
  public void noEchoAboveGround() {
    assertFalse(TTSDialoguePlugin.shouldEchoLine(VoiceBackend.CLOUD, true, false));
  }
}
