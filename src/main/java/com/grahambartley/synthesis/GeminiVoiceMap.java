package com.grahambartley.synthesis;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import java.util.EnumMap;
import java.util.Map;

/**
 * Maps a backend-neutral {@link VoiceSpec} onto a concrete Gemini 3.1 Flash TTS voice name.
 *
 * <p>Gemini exposes 30 prebuilt voices identified only by name and a vibe adjective (Charon is
 * "Informative", Algenib is "Gravelly", and so on); the API carries no gender metadata, so the
 * male/female split here is confirmed by ear from a generated sample pack rather than read from the
 * API. Each race/gender pair anchors to a small, gender-correct sub-pool of voices chosen for race
 * character (gravelly timbres for dwarves and trolls, refined for elves and wizards, light for
 * goblins, breathy for the undead). Two NPCs of the same race and gender are spread across that
 * sub-pool by the per-NPC seed already stamped on the spec (issue #78), so they sound distinct but
 * stable across sessions.
 *
 * <p>Gender-correctness is structural: a male spec can only ever resolve to a voice from a male
 * sub-pool and a female spec to a female sub-pool, so no race maps two genders onto the same voice.
 * The player respects the configured player-voice gender. {@link #UNKNOWN} race and unknown gender
 * fall back to the neutral human-male anchor so every spec resolves to a real voice.
 */
final class GeminiVoiceMap {

  /** Neutral default when a spec has no specific mapping (a clear, even male voice). */
  static final String DEFAULT_VOICE = "Charon";

  private final Map<NPCRace, Map<NPCGender, String[]>> npcVoices;
  private final Map<NPCGender, String[]> playerVoices;

  GeminiVoiceMap() {
    playerVoices = new EnumMap<>(NPCGender.class);
    playerVoices.put(NPCGender.MALE, new String[] {"Achird", "Iapetus"});
    playerVoices.put(NPCGender.FEMALE, new String[] {"Aoede", "Autonoe"});

    npcVoices = new EnumMap<>(NPCRace.class);
    // Voice depth is inferred from the catalog's character adjectives: gravelly (Algenib), firm
    // (Alnilam, Orus), even (Schedar), breathy (Enceladus) and informative (Charon, Rasalgethi,
    // Sadaltager) are the deep, mature end; upbeat (Puck) and casual (Zubenelgenubi) are bright.
    // Big,
    // imposing races (troll/ogre, demon, undead, dwarf) anchor to the deep end so they sound large
    // rather than high-pitched; goblins stay deliberately bright and small.
    // Human (most common): clear, neutral, mid-depth voices.
    put(NPCRace.HUMAN, male("Charon", "Iapetus"), female("Despina", "Erinome"));
    // Elf (refined, elegant): clear/refined voices.
    put(NPCRace.ELF, male("Iapetus", "Rasalgethi"), female("Vindemiatrix", "Erinome"));
    // Dwarf (gruff, sturdy): gravelly/firm, deep.
    put(NPCRace.DWARF, male("Algenib", "Alnilam"), female("Gacrux", "Kore"));
    // Goblin (small, crude): bright/light voices, deliberately high.
    put(NPCRace.GOBLIN, male("Puck", "Zubenelgenubi"), female("Leda", "Laomedeia"));
    // Monkey (small, quick, chattery): bright, energetic, playful.
    put(NPCRace.MONKEY, male("Fenrir", "Sadachbia"), female("Zephyr", "Pulcherrima"));
    // Gorilla (huge, booming, primal): gravelly/firm, anchored to the deepest end.
    put(NPCRace.GORILLA, male("Algenib", "Orus"), female("Gacrux", "Kore"));
    // Troll/ogre (big, lumbering): gravelly/firm, the deepest male timbres.
    put(NPCRace.TROLL, male("Algenib", "Orus"), female("Gacrux", "Kore"));
    // Undead (hollow, eerie): breathy/even, deep and cold.
    put(NPCRace.UNDEAD, male("Enceladus", "Schedar"), female("Achernar", "Sulafat"));
    // Demon (booming, sinister): gravelly/informative, the deepest.
    put(NPCRace.DEMON, male("Algenib", "Rasalgethi"), female("Gacrux", "Despina"));
    // Wizard (wise, mystical): knowledgeable/informative, weighty.
    put(NPCRace.WIZARD, male("Sadaltager", "Charon"), female("Sulafat", "Vindemiatrix"));
    // Tortugan (warm island folk): friendly/clear and warm/gentle, relaxed mid-depth.
    put(NPCRace.TORTUGAN, male("Achird", "Iapetus"), female("Sulafat", "Vindemiatrix"));
  }

  private static String[] male(String... voices) {
    return voices;
  }

  private static String[] female(String... voices) {
    return voices;
  }

  private void put(NPCRace race, String[] maleVoices, String[] femaleVoices) {
    Map<NPCGender, String[]> byGender = new EnumMap<>(NPCGender.class);
    byGender.put(NPCGender.MALE, maleVoices);
    byGender.put(NPCGender.FEMALE, femaleVoices);
    npcVoices.put(race, byGender);
  }

  /**
   * Resolves the Gemini voice for a spec. The per-NPC seed on the spec (issue #78) spreads
   * same-race/gender NPCs across the sub-pool deterministically; a spec with no seed resolves to
   * the first (anchor) voice of its race/gender pool, so bare specs are stable too.
   */
  String voiceFor(VoiceSpec spec) {
    if (spec == null) {
      return DEFAULT_VOICE;
    }
    NPCGender gender = normalizeGender(spec.gender());
    if (spec.player()) {
      return pick(playerVoices.get(gender), spec);
    }
    Map<NPCGender, String[]> byGender = npcVoices.get(spec.race());
    if (byGender == null) {
      return pick(playerVoices.get(gender), spec);
    }
    return pick(byGender.get(gender), spec);
  }

  /**
   * Spreads across {@code pool} by the spec's stable per-NPC seed, anchoring bare specs at index 0.
   */
  private static String pick(String[] pool, VoiceSpec spec) {
    if (pool == null || pool.length == 0) {
      return DEFAULT_VOICE;
    }
    if (!spec.hasExplicitKokoroSpeakerId()) {
      return pool[0];
    }
    int index = Math.floorMod(Integer.hashCode(spec.kokoroSpeakerId()), pool.length);
    return pool[index];
  }

  /** Unknown gender is voiced from the male sub-pool so every spec resolves to a concrete voice. */
  private static NPCGender normalizeGender(NPCGender gender) {
    return gender == NPCGender.FEMALE ? NPCGender.FEMALE : NPCGender.MALE;
  }
}
