package com.grahambartley.tts;

import com.grahambartley.TTSDialogueConfig;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Decides whether a line should be rendered with the cave echo: only on the Cloud backend, with the
 * toggle on, while the player is below the overworld (a cave, dungeon, sewer or basement). The
 * coordinate predicate and the echo gate are pure (client-free) so they are unit-testable; {@link
 * #isUnderground} reads the client and must be called on the game thread.
 */
@Slf4j
public final class CaveEchoPolicy {

  /**
   * Player-owned house instance template regions. A POH is built from a fixed block of template
   * chunks that straddles region boundaries, so {@link WorldPoint#fromLocalInstance} resolves house
   * tiles into more than one region. These all sit in the high-{@code Y} band like a cave but are
   * an enclosed surface instance, so they are excluded from the underground test.
   */
  static final Set<Integer> POH_REGION_IDS =
      Set.of(7257, 7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303);

  private final Client client;
  private final TTSDialogueConfig config;

  public CaveEchoPolicy(Client client, TTSDialogueConfig config) {
    this.client = client;
    this.config = config;
  }

  /** The live echo decision for the current line: Cloud + toggle on + underground. */
  public boolean shouldEcho() {
    return shouldEchoLine(config.voiceBackend(), config.cloudCaveEcho(), isUnderground());
  }

  /**
   * Pure gate for the cave echo: render an echo only on the Cloud backend, with the toggle on,
   * while the player is underground.
   */
  static boolean shouldEchoLine(
      TTSDialogueConfig.VoiceBackend backend, boolean caveEchoEnabled, boolean underground) {
    return backend == TTSDialogueConfig.VoiceBackend.CLOUD && caveEchoEnabled && underground;
  }

  /**
   * Whether the local player is below the overworld. Reads the client only on the game thread.
   * Resolves instanced chunks to their template world coordinate so an instanced cave still reads
   * as a cave.
   */
  boolean isUnderground() {
    Player local = client.getLocalPlayer();
    if (local == null) {
      return false;
    }
    LocalPoint lp = local.getLocalLocation();
    WorldPoint wp =
        client.isInInstancedRegion() && lp != null
            ? WorldPoint.fromLocalInstance(client, lp)
            : local.getWorldLocation();
    if (wp == null) {
      return false;
    }
    boolean underground = isUndergroundPoint(wp);
    if (config.debugMode()) {
      log.info(
          "[TTS echo] x={} y={} plane={} region={} instanced={} underground={}",
          wp.getX(),
          wp.getY(),
          wp.getPlane(),
          wp.getRegionID(),
          client.isInInstancedRegion(),
          underground);
    }
    return underground;
  }

  /**
   * Pure, client-free core of the underground test: a point is underground when its
   * mirror-corrected world {@code Y} sits at or above {@link Constants#OVERWORLD_MAX_Y}, the
   * coordinate convention the game map is built on (every cave/dungeon is displaced north of the
   * overworld). The mirror step normalises Prifddinas, the one surface area whose real geometry
   * sits in that band, and the player-owned house ({@link #POH_REGION_IDS}) is carved out as an
   * enclosed surface instance that also lands in the band.
   */
  static boolean isUndergroundPoint(WorldPoint wp) {
    if (POH_REGION_IDS.contains(wp.getRegionID())) {
      return false;
    }
    return WorldPoint.getMirrorPoint(wp, true).getY() >= Constants.OVERWORLD_MAX_Y;
  }
}
