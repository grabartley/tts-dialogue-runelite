package com.grahambartley.synthesis;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import java.util.EnumMap;
import java.util.Map;

/**
 * Maps a backend-neutral {@link VoiceSpec} (player or NPC race/gender) onto a Zonos reference-voice
 * id, mirroring the spirit of the Kokoro {@code VoiceProfile} matrix and {@link AzureVoiceMap}: one
 * distinct voice per race and gender so categories stay differentiable.
 *
 * <p>Zonos conditions on a speaker embedding derived from a short reference clip; the engine bundle
 * ships a small bank of named reference voices and resolves these ids to embeddings. The plugin
 * only needs to pick a stable id per spec, so the mapping is a plain string table here and the
 * heavy embedding work stays in the engine. The naming groups by character so a spec that has no
 * explicit mapping still falls back to a sensible default rather than failing.
 */
final class ZonosVoiceMap {

  /** Voice used when a spec has no specific mapping (a neutral, widely usable reference voice). */
  static final String DEFAULT_VOICE = "narrator_neutral";

  private final Map<NPCRace, Map<NPCGender, String>> npcVoices;
  private final Map<NPCGender, String> playerVoices;

  ZonosVoiceMap() {
    playerVoices = new EnumMap<>(NPCGender.class);
    playerVoices.put(NPCGender.MALE, "player_male");
    playerVoices.put(NPCGender.FEMALE, "player_female");

    npcVoices = new EnumMap<>(NPCRace.class);
    // Human (most common) - clear, neutral delivery.
    put(NPCRace.HUMAN, "human_male", "human_female");
    // Elf (elegant, refined).
    put(NPCRace.ELF, "elf_male", "elf_female");
    // Dwarf (gruff, sturdy).
    put(NPCRace.DWARF, "dwarf_male", "dwarf_female");
    // Goblin (small, crude).
    put(NPCRace.GOBLIN, "goblin_male", "goblin_female");
    // Troll (big, deep, primitive).
    put(NPCRace.TROLL, "troll_male", "troll_female");
    // Undead (hollow, eerie).
    put(NPCRace.UNDEAD, "undead_male", "undead_female");
    // Demon (sinister, otherworldly).
    put(NPCRace.DEMON, "demon_male", "demon_female");
    // Wizard (wise, mystical).
    put(NPCRace.WIZARD, "wizard_male", "wizard_female");
  }

  private void put(NPCRace race, String male, String female) {
    Map<NPCGender, String> byGender = new EnumMap<>(NPCGender.class);
    byGender.put(NPCGender.MALE, male);
    byGender.put(NPCGender.FEMALE, female);
    npcVoices.put(race, byGender);
  }

  /** Resolves the Zonos reference-voice id for a spec, falling back to {@link #DEFAULT_VOICE}. */
  String voiceFor(VoiceSpec spec) {
    if (spec == null) {
      return DEFAULT_VOICE;
    }
    NPCGender gender = normalizeGender(spec.gender());
    if (spec.player()) {
      return playerVoices.getOrDefault(gender, DEFAULT_VOICE);
    }
    Map<NPCGender, String> byGender = npcVoices.get(spec.race());
    if (byGender == null) {
      return DEFAULT_VOICE;
    }
    return byGender.getOrDefault(gender, DEFAULT_VOICE);
  }

  /** Unknown gender is voiced with the male slot so every spec resolves to a concrete voice. */
  private static NPCGender normalizeGender(NPCGender gender) {
    return gender == NPCGender.FEMALE ? NPCGender.FEMALE : NPCGender.MALE;
  }
}
