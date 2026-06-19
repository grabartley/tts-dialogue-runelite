package com.grahambartley.synthesis;

import com.grahambartley.VoiceManager;
import java.util.Objects;

/**
 * Backend-neutral description of <em>who</em> is speaking: the player, or an NPC of a given race
 * and gender.
 *
 * <p>This carries the resolved race/gender categories rather than any engine-specific voice id, so
 * every backend can map the same spec to its own voice bank. The Kokoro backend turns it into a
 * speaker id via {@link VoiceManager#kokoroSpeakerId(VoiceSpec)}; other backends map it
 * differently. {@link #key()} produces a stable, human-readable fragment used in the synthesis
 * cache key.
 */
public final class VoiceSpec {

  private final boolean player;
  private final VoiceManager.NPCRace race;
  private final VoiceManager.NPCGender gender;

  private VoiceSpec(boolean player, VoiceManager.NPCRace race, VoiceManager.NPCGender gender) {
    this.player = player;
    this.race = race;
    this.gender = gender;
  }

  /** A player voice of the given gender. Race is not meaningful for the player. */
  public static VoiceSpec player(VoiceManager.NPCGender gender) {
    return new VoiceSpec(true, VoiceManager.NPCRace.HUMAN, gender);
  }

  /** An NPC voice for the given race and gender. */
  public static VoiceSpec npc(VoiceManager.NPCRace race, VoiceManager.NPCGender gender) {
    return new VoiceSpec(false, race, gender);
  }

  public boolean isPlayer() {
    return player;
  }

  public VoiceManager.NPCRace getRace() {
    return race;
  }

  public VoiceManager.NPCGender getGender() {
    return gender;
  }

  /**
   * Stable cache-key fragment, e.g. {@code "npc:ELF:FEMALE"} or {@code "player:MALE"}. Two specs
   * that resolve to the same voice produce the same key.
   */
  public String key() {
    return player ? "player:" + gender : "npc:" + race + ":" + gender;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VoiceSpec)) {
      return false;
    }
    VoiceSpec other = (VoiceSpec) o;
    return player == other.player && race == other.race && gender == other.gender;
  }

  @Override
  public int hashCode() {
    return Objects.hash(player, race, gender);
  }

  @Override
  public String toString() {
    return key();
  }
}
