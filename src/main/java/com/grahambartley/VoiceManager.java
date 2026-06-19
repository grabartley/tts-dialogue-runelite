package com.grahambartley;

import com.grahambartley.data.NPCAttributes;
import com.grahambartley.data.NPCDemographicAnalyzer;
import com.grahambartley.synthesis.VoiceSpec;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;

/**
 * Resolves an NPC (or the player) to a backend-neutral {@link VoiceSpec} and, for the local Kokoro
 * backend, to a concrete Kokoro speaker id.
 *
 * <p>Resolution carries race/gender categories so any backend can map the same speaker to its own
 * voice bank. The local Kokoro backend turns a spec into a real speaker id via {@link
 * #kokoroSpeakerId(VoiceSpec)} from the {@link VoiceProfile} matrix: each race/gender category maps
 * to a distinct, clean Kokoro voice so the neural output is the product as-is, with no resampling
 * pitch shift, reverb, or distortion.
 */
@Slf4j
public class VoiceManager {

  public enum NPCRace {
    HUMAN,
    ELF,
    DWARF,
    GOBLIN,
    TROLL,
    UNDEAD,
    DEMON,
    WIZARD,
    UNKNOWN
  }

  public enum NPCGender {
    MALE,
    FEMALE,
    UNKNOWN
  }

  /**
   * Race/gender voice matrix mapped onto distinct speakers from the {@code kokoro-multi-lang-v1_0}
   * bank. Only the English voices are used (American {@code af_/am_} and British {@code bf_/bm_},
   * speaker ids 0-27), picked so each category is clearly differentiable: British accents read as
   * refined (elf, wizard), deeper timbres as gruff (dwarf, troll, demon), lighter timbres as small
   * and crude (goblin), and a whispery voice as eerie (undead). Every entry is a unique speaker id,
   * so categories never collide. See README for the full table.
   */
  public enum VoiceProfile {
    // Player voices
    PLAYER_MALE(16, "am_michael", "Player Male", NPCRace.HUMAN, NPCGender.MALE),
    PLAYER_FEMALE(3, "af_heart", "Player Female", NPCRace.HUMAN, NPCGender.FEMALE),

    // Human voices (most common NPCs) - clear, neutral
    HUMAN_MALE(14, "am_fenrir", "Human Male", NPCRace.HUMAN, NPCGender.MALE),
    HUMAN_FEMALE(2, "af_bella", "Human Female", NPCRace.HUMAN, NPCGender.FEMALE),

    // Elf voices (elegant, refined) - British accent
    ELF_MALE(26, "bm_george", "Elf Male", NPCRace.ELF, NPCGender.MALE),
    ELF_FEMALE(21, "bf_emma", "Elf Female", NPCRace.ELF, NPCGender.FEMALE),

    // Dwarf voices (gruff, sturdy) - deeper British timbres
    DWARF_MALE(27, "bm_lewis", "Dwarf Male", NPCRace.DWARF, NPCGender.MALE),
    DWARF_FEMALE(22, "bf_isabella", "Dwarf Female", NPCRace.DWARF, NPCGender.FEMALE),

    // Goblin voices (small, crude) - lighter, mischievous
    GOBLIN_MALE(18, "am_puck", "Goblin Male", NPCRace.GOBLIN, NPCGender.MALE),
    GOBLIN_FEMALE(10, "af_sky", "Goblin Female", NPCRace.GOBLIN, NPCGender.FEMALE),

    // Troll voices (big, deep, primitive)
    TROLL_MALE(17, "am_onyx", "Troll Male", NPCRace.TROLL, NPCGender.MALE),
    TROLL_FEMALE(9, "af_sarah", "Troll Female", NPCRace.TROLL, NPCGender.FEMALE),

    // Undead voices (hollow, eerie)
    UNDEAD_MALE(12, "am_echo", "Undead Male", NPCRace.UNDEAD, NPCGender.MALE),
    UNDEAD_FEMALE(6, "af_nicole", "Undead Female", NPCRace.UNDEAD, NPCGender.FEMALE),

    // Demon voices (sinister, deep, otherworldly)
    DEMON_MALE(24, "bm_daniel", "Demon Male", NPCRace.DEMON, NPCGender.MALE),
    DEMON_FEMALE(8, "af_river", "Demon Female", NPCRace.DEMON, NPCGender.FEMALE),

    // Wizard voices (wise, mystical) - storyteller British male, distinct female
    WIZARD_MALE(25, "bm_fable", "Wizard Male", NPCRace.WIZARD, NPCGender.MALE),
    WIZARD_FEMALE(0, "af_alloy", "Wizard Female", NPCRace.WIZARD, NPCGender.FEMALE);

    private final int speakerId;
    private final String kokoroVoice;
    private final String displayName;
    private final NPCRace race;
    private final NPCGender gender;

    VoiceProfile(
        int speakerId, String kokoroVoice, String displayName, NPCRace race, NPCGender gender) {
      this.speakerId = speakerId;
      this.kokoroVoice = kokoroVoice;
      this.displayName = displayName;
      this.race = race;
      this.gender = gender;
    }

    /** The race category this profile voices. */
    public NPCRace getRace() {
      return race;
    }

    /** The gender category this profile voices. */
    public NPCGender getGender() {
      return gender;
    }

    /** The Kokoro speaker id fed straight to the in-process synth engine. */
    public int getSpeakerId() {
      return speakerId;
    }

    /** The underlying Kokoro voice name (for docs and logs). */
    public String getKokoroVoice() {
      return kokoroVoice;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  private final TTSDialogueConfig config;
  private final Client client;
  private final NPCDemographicAnalyzer demographicAnalyzer;

  public VoiceManager(TTSDialogueConfig config, Client client) {
    this.config = config;
    this.client = client;
    this.demographicAnalyzer = new NPCDemographicAnalyzer();
    this.demographicAnalyzer.initialize();
  }

  /**
   * Resolves a backend-neutral {@link VoiceSpec} for a line of dialogue. The player uses the gender
   * of the configured player voice; NPCs use their race/gender voice when automatic voices are
   * enabled, otherwise the default NPC voice. Fallback semantics are unchanged.
   */
  public VoiceSpec resolveVoice(String speaker, String npcName) {
    if ("player".equalsIgnoreCase(speaker)) {
      return VoiceSpec.player(playerGender());
    }
    VoiceProfile profile =
        config.enableRaceBasedVoices() ? getVoiceForNPC(npcName) : getDefaultNPCVoice();
    return profileToSpec(profile);
  }

  /**
   * The Kokoro speaker id for a resolved {@link VoiceSpec}, used only by the local Kokoro backend.
   * The player maps to the configured player voice; NPCs map through the {@link VoiceProfile}
   * matrix.
   */
  public int kokoroSpeakerId(VoiceSpec voice) {
    if (voice.player()) {
      // Derive the player speaker id from the spec's gender, not by re-reading config. The spec was
      // stamped from config when the line was resolved, so this keeps the cache key and the audio
      // in
      // agreement even if the player voice is switched between resolution and synthesis.
      return voice.gender() == NPCGender.FEMALE
          ? VoiceProfile.PLAYER_FEMALE.getSpeakerId()
          : VoiceProfile.PLAYER_MALE.getSpeakerId();
    }
    return getVoiceForRaceAndGender(voice.race(), voice.gender()).getSpeakerId();
  }

  /** Gender implied by the configured player voice profile. */
  private NPCGender playerGender() {
    return config.playerVoice().getGender();
  }

  /** Turns a resolved NPC {@link VoiceProfile} back into its race/gender {@link VoiceSpec}. */
  private static VoiceSpec profileToSpec(VoiceProfile profile) {
    return VoiceSpec.npc(profile.getRace(), profile.getGender());
  }

  /** Determines the appropriate voice for an NPC based on their race and gender. */
  public VoiceProfile getVoiceForNPC(String npcName) {
    if (npcName == null || npcName.isEmpty()) {
      return getFallbackVoice(NPCGender.UNKNOWN);
    }

    NPC npc = findNPCByName(npcName);
    if (npc == null) {
      log.debug("Could not find NPC '{}' in game world, using fallback voice", npcName);
      return getFallbackVoice(NPCGender.UNKNOWN);
    }

    NPCAttributes attributes = demographicAnalyzer.analyzeNPC(npc);
    if (attributes == null) {
      log.debug("Analysis failed for NPC '{}', using fallback voice", npcName);
      return getFallbackVoice(NPCGender.UNKNOWN);
    }

    NPCRace race = convertToNPCRace(attributes.getRace());
    NPCGender gender = convertToNPCGender(attributes.getGender());

    // An unrecognised race falls back rather than silently borrowing the human voice, so the
    // fallback toggle stays meaningful.
    if (race == NPCRace.UNKNOWN) {
      return getFallbackVoice(gender);
    }

    VoiceProfile voice = getVoiceForRaceAndGender(race, gender);
    log.debug(
        "NPC '{}' (ID {}) -> Race {}, Gender {} -> {} ({}) [source {}, confidence {}]",
        npcName,
        npc.getId(),
        race,
        gender,
        voice.getDisplayName(),
        voice.getKokoroVoice(),
        attributes.getSource(),
        attributes.getConfidence());
    return voice;
  }

  /** Find NPC entity by name in the current game world. */
  private NPC findNPCByName(String targetName) {
    if (client == null || client.getNpcs() == null) {
      return null;
    }

    return client.getNpcs().stream()
        .filter(npc -> npc != null && npc.getName() != null)
        .filter(npc -> npc.getName().equals(targetName))
        .findFirst()
        .orElse(null);
  }

  /** Get voice profile for a known race and gender combination. */
  private VoiceProfile getVoiceForRaceAndGender(NPCRace race, NPCGender gender) {
    boolean female = gender == NPCGender.FEMALE;
    switch (race) {
      case ELF:
        return female ? VoiceProfile.ELF_FEMALE : VoiceProfile.ELF_MALE;
      case DWARF:
        return female ? VoiceProfile.DWARF_FEMALE : VoiceProfile.DWARF_MALE;
      case GOBLIN:
        return female ? VoiceProfile.GOBLIN_FEMALE : VoiceProfile.GOBLIN_MALE;
      case TROLL:
        return female ? VoiceProfile.TROLL_FEMALE : VoiceProfile.TROLL_MALE;
      case UNDEAD:
        return female ? VoiceProfile.UNDEAD_FEMALE : VoiceProfile.UNDEAD_MALE;
      case DEMON:
        return female ? VoiceProfile.DEMON_FEMALE : VoiceProfile.DEMON_MALE;
      case WIZARD:
        return female ? VoiceProfile.WIZARD_FEMALE : VoiceProfile.WIZARD_MALE;
      case HUMAN:
      default:
        return female ? VoiceProfile.HUMAN_FEMALE : VoiceProfile.HUMAN_MALE;
    }
  }

  /**
   * Voice used when race detection fails or the race is unrecognised. With fallbacks enabled the
   * caller still gets a gender-appropriate human voice; with fallbacks disabled everything
   * collapses to a single default voice.
   */
  private VoiceProfile getFallbackVoice(NPCGender gender) {
    if (!config.enableFallbacks()) {
      return getDefaultNPCVoice();
    }
    return gender == NPCGender.FEMALE ? VoiceProfile.HUMAN_FEMALE : VoiceProfile.HUMAN_MALE;
  }

  /** The single default NPC voice (Human Male). */
  private VoiceProfile getDefaultNPCVoice() {
    return VoiceProfile.HUMAN_MALE;
  }

  /** Convert string race attribute to NPCRace enum. */
  private NPCRace convertToNPCRace(String race) {
    if (race == null || race.isEmpty()) {
      return NPCRace.UNKNOWN;
    }

    try {
      return NPCRace.valueOf(race.toUpperCase());
    } catch (IllegalArgumentException e) {
      // Handle mappings for races not directly in our enum
      String raceLower = race.toLowerCase();

      if (raceLower.contains("human")
          || raceLower.contains("man")
          || raceLower.contains("person")) {
        return NPCRace.HUMAN;
      } else if (raceLower.contains("elf") || raceLower.contains("elven")) {
        return NPCRace.ELF;
      } else if (raceLower.contains("dwarf") || raceLower.contains("dwarven")) {
        return NPCRace.DWARF;
      } else if (raceLower.contains("goblin") || raceLower.contains("gnome")) {
        return NPCRace.GOBLIN;
      } else if (raceLower.contains("troll") || raceLower.contains("giant")) {
        return NPCRace.TROLL;
      } else if (raceLower.contains("undead")
          || raceLower.contains("skeleton")
          || raceLower.contains("zombie")
          || raceLower.contains("ghost")) {
        return NPCRace.UNDEAD;
      } else if (raceLower.contains("demon")
          || raceLower.contains("dragon")
          || raceLower.contains("devil")) {
        return NPCRace.DEMON;
      } else if (raceLower.contains("wizard") || raceLower.contains("mage")) {
        return NPCRace.WIZARD;
      }

      log.debug("Unknown race '{}', using fallback voice", race);
      return NPCRace.UNKNOWN;
    }
  }

  /** Convert string gender attribute to NPCGender enum. */
  private NPCGender convertToNPCGender(String gender) {
    if (gender == null || gender.isEmpty()) {
      return NPCGender.UNKNOWN;
    }

    try {
      return NPCGender.valueOf(gender.toUpperCase());
    } catch (IllegalArgumentException e) {
      // Handle various gender representations
      String genderLower = gender.toLowerCase();

      if (genderLower.contains("female")
          || genderLower.contains("woman")
          || genderLower.contains("girl")
          || genderLower.contains("lady")) {
        return NPCGender.FEMALE;
      } else if (genderLower.contains("male")
          || genderLower.contains("man")
          || genderLower.contains("boy")
          || genderLower.contains("lord")) {
        return NPCGender.MALE;
      }

      log.debug("Unknown gender '{}', defaulting to MALE", gender);
      return NPCGender.MALE;
    }
  }
}
