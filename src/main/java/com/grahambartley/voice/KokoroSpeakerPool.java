package com.grahambartley.voice;

import com.grahambartley.voice.VoiceManager.KokoroVoice;
import com.grahambartley.voice.VoiceManager.NPCGender;
import java.util.TreeSet;

/**
 * Gender-appropriate British speaker pools for per-NPC voice variety (#78). Built once from the
 * {@link KokoroVoice} bank, sorted ascending so a given NPC always hashes to the same slot across
 * runs. Selection is by gender alone: a male NPC can only ever map to a male voice and a female NPC
 * to a female voice. The player resolves to a fixed British voice per gender.
 */
final class KokoroSpeakerPool {

  static final int[] MALE_SPEAKER_POOL = poolFor(NPCGender.MALE);

  static final int[] FEMALE_SPEAKER_POOL = poolFor(NPCGender.FEMALE);

  /** The British voice the player resolves to, by gender. */
  private static final int PLAYER_MALE_SPEAKER = KokoroVoice.BM_GEORGE.getSpeakerId();

  private static final int PLAYER_FEMALE_SPEAKER = KokoroVoice.BF_EMMA.getSpeakerId();

  private KokoroSpeakerPool() {}

  private static int[] poolFor(NPCGender gender) {
    TreeSet<Integer> ids = new TreeSet<>();
    for (KokoroVoice voice : KokoroVoice.values()) {
      if (voice.getGender() == gender) {
        ids.add(voice.getSpeakerId());
      }
    }
    int[] pool = new int[ids.size()];
    int i = 0;
    for (int id : ids) {
      pool[i++] = id;
    }
    return pool;
  }

  /** The British voice the player speaks with, fixed by gender. */
  static int playerSpeaker(NPCGender gender) {
    return gender == NPCGender.FEMALE ? PLAYER_FEMALE_SPEAKER : PLAYER_MALE_SPEAKER;
  }

  /**
   * Picks a stable, gender-correct British speaker for a specific NPC (#78). NPCs of the same
   * gender are spread across the gender pool by hashing a per-NPC key into the pool index.
   *
   * <p>Keying rule (documented contract): the NPC's composition id is preferred (the same NPC type
   * always hashes the same way, regardless of how its name was presented); when no id is available
   * the normalised name is the fallback key. The pool index is {@code Math.floorMod(hash, size)} so
   * it is non-negative and deterministic. An unknown gender uses the male pool, mirroring detection
   * defaulting an unknown gender to male.
   */
  static int pickNpcSpeakerId(NPCGender gender, Integer npcId, String npcName) {
    int[] pool = gender == NPCGender.FEMALE ? FEMALE_SPEAKER_POOL : MALE_SPEAKER_POOL;
    int hash =
        npcId != null ? Integer.hashCode(npcId) : NameNormalizer.normalize(npcName).hashCode();
    return pool[Math.floorMod(hash, pool.length)];
  }
}
