package com.grahambartley.synthesis;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import java.util.EnumMap;
import java.util.Map;

/**
 * Maps a backend-neutral {@link VoiceSpec} (player or NPC race/gender) onto a concrete Azure neural
 * voice name, mirroring the spirit of the Kokoro {@code VoiceProfile} matrix: distinct {@code
 * en-US}/{@code en-GB} neural voices per race and gender so categories stay differentiable. British
 * voices read as refined (elf, wizard), deeper US voices as gruff (dwarf, troll, demon), lighter
 * voices as small and crude (goblin), and a softer voice as eerie (undead).
 *
 * <p>Every chosen voice is a multi-style neural voice that supports the emotional styles this
 * plugin emits ({@code cheerful}, {@code sad}, {@code angry}, {@code terrified}). {@link
 * AzureVoiceStyles#supports} records each voice's real supported-style set so {@link AzureSsml}
 * degrades to plain delivery when a voice lacks a requested style.
 */
final class AzureVoiceMap {

  /** Voice used when a spec has no specific mapping (a widely available multi-style US voice). */
  static final String DEFAULT_VOICE = "en-US-AriaNeural";

  private final Map<NPCRace, Map<NPCGender, String>> npcVoices;
  private final Map<NPCGender, String> playerVoices;

  AzureVoiceMap() {
    playerVoices = new EnumMap<>(NPCGender.class);
    // Player: clear, expressive multi-style voices.
    playerVoices.put(NPCGender.MALE, "en-US-GuyNeural");
    playerVoices.put(NPCGender.FEMALE, "en-US-JaneNeural");

    npcVoices = new EnumMap<>(NPCRace.class);

    // Human (most common) - clear, neutral US voices.
    put(NPCRace.HUMAN, "en-US-DavisNeural", "en-US-AriaNeural");
    // Elf (elegant, refined) - British accents.
    put(NPCRace.ELF, "en-GB-RyanNeural", "en-GB-SoniaNeural");
    // Dwarf (gruff, sturdy) - deeper British male, firmer female.
    put(NPCRace.DWARF, "en-GB-ThomasNeural", "en-GB-LibbyNeural");
    // Goblin (small, crude) - lighter, livelier voices.
    put(NPCRace.GOBLIN, "en-US-TonyNeural", "en-US-AshleyNeural");
    // Troll (big, deep, primitive) - deep US voices.
    put(NPCRace.TROLL, "en-US-JasonNeural", "en-US-NancyNeural");
    // Undead (hollow, eerie) - softer, distant voices.
    put(NPCRace.UNDEAD, "en-US-ChristopherNeural", "en-US-SaraNeural");
    // Demon (sinister, deep, otherworldly).
    put(NPCRace.DEMON, "en-US-BrandonNeural", "en-US-MonicaNeural");
    // Wizard (wise, mystical) - storyteller British male, distinct US female.
    put(NPCRace.WIZARD, "en-GB-AlfieNeural", "en-US-CoraNeural");
  }

  private void put(NPCRace race, String male, String female) {
    Map<NPCGender, String> byGender = new EnumMap<>(NPCGender.class);
    byGender.put(NPCGender.MALE, male);
    byGender.put(NPCGender.FEMALE, female);
    npcVoices.put(race, byGender);
  }

  /** Resolves the Azure neural voice name for a spec, falling back to {@link #DEFAULT_VOICE}. */
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
