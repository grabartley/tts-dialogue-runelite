package com.grahambartley.synthesis;

import com.grahambartley.VoiceManager;

/**
 * Backend-neutral description of <em>who</em> is speaking: the player, or an NPC of a given race
 * and gender.
 *
 * <p>This carries the resolved race/gender categories rather than any engine-specific voice id, so
 * every backend can map the same spec to its own voice bank. The Kokoro backend turns it into a
 * speaker id via {@link VoiceManager#kokoroSpeakerId(VoiceSpec)}; other backends map it
 * differently. {@link #key()} produces a stable, human-readable fragment used in the synthesis
 * cache key.
 *
 * <p>For per-NPC voice variety (issue #78) an NPC spec may additionally carry an explicit {@code
 * kokoroSpeakerId} picked from a gender-appropriate pool, so two NPCs of the same race+gender can
 * speak with different (but stable) voices. The id is Kokoro-specific and purely advisory: backends
 * that map by race/gender (Azure, Zonos) ignore it, and {@link #UNSPECIFIED_SPEAKER_ID} ({@code
 * -1}) means "no explicit choice" so the engine falls back to its race/gender matrix exactly as
 * before.
 */
public record VoiceSpec(
    boolean player, VoiceManager.NPCRace race, VoiceManager.NPCGender gender, int kokoroSpeakerId) {

  /** No explicit Kokoro speaker id: the engine falls back to its race/gender matrix. */
  public static final int UNSPECIFIED_SPEAKER_ID = -1;

  /** A player voice of the given gender. Race is not meaningful for the player. */
  public static VoiceSpec player(VoiceManager.NPCGender gender) {
    return new VoiceSpec(true, VoiceManager.NPCRace.HUMAN, gender, UNSPECIFIED_SPEAKER_ID);
  }

  /** An NPC voice for the given race and gender, with no explicit per-NPC Kokoro speaker. */
  public static VoiceSpec npc(VoiceManager.NPCRace race, VoiceManager.NPCGender gender) {
    return new VoiceSpec(false, race, gender, UNSPECIFIED_SPEAKER_ID);
  }

  /**
   * An NPC voice for the given race and gender carrying an explicit per-NPC Kokoro {@code
   * speakerId} (issue #78). A negative id is normalised to {@link #UNSPECIFIED_SPEAKER_ID} so it is
   * treated as absent.
   */
  public static VoiceSpec npc(
      VoiceManager.NPCRace race, VoiceManager.NPCGender gender, int speakerId) {
    return new VoiceSpec(false, race, gender, speakerId < 0 ? UNSPECIFIED_SPEAKER_ID : speakerId);
  }

  /** Whether this spec carries an explicit per-NPC Kokoro speaker id. */
  public boolean hasExplicitKokoroSpeakerId() {
    return kokoroSpeakerId >= 0;
  }

  /**
   * Stable cache-key fragment, e.g. {@code "npc:ELF:FEMALE"} or {@code "player:MALE"}. Two specs
   * that resolve to the same voice produce the same key. When an explicit per-NPC Kokoro speaker id
   * is present it is appended (e.g. {@code "npc:HUMAN:MALE#14"}) so two NPCs of the same
   * race+gender that picked different voices never share a cached audio frame.
   */
  public String key() {
    String base = player ? "player:" + gender : "npc:" + race + ":" + gender;
    return hasExplicitKokoroSpeakerId() ? base + "#" + kokoroSpeakerId : base;
  }

  @Override
  public String toString() {
    return key();
  }
}
