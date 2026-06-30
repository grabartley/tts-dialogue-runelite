package com.grahambartley.voice;

import com.grahambartley.voice.VoiceManager.NPCGender;
import com.grahambartley.voice.VoiceManager.NPCRace;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps the raw race/gender strings carried on detected NPC attributes (from the bundled table or a
 * learned wiki lookup) onto the {@link NPCRace}/{@link NPCGender} enums. An exact enum-name match
 * wins; otherwise a keyword scan buckets common variants. An unrecognised race is {@link
 * NPCRace#UNKNOWN}; an unrecognised gender defaults to {@link NPCGender#MALE}, the long-standing
 * fallback.
 */
@Slf4j
final class NpcDemographicParser {

  private NpcDemographicParser() {}

  static NPCRace toRace(String race) {
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
      } else if (raceLower.contains("gorilla")) {
        return NPCRace.GORILLA;
      } else if (raceLower.contains("monkey") || raceLower.contains("primate")) {
        return NPCRace.MONKEY;
      } else if (raceLower.contains("wizard") || raceLower.contains("mage")) {
        return NPCRace.WIZARD;
      } else if (raceLower.contains("tortugan") || raceLower.contains("tortuga")) {
        return NPCRace.TORTUGAN;
      }

      log.debug("Unknown race '{}', using default voice", race);
      return NPCRace.UNKNOWN;
    }
  }

  static NPCGender toGender(String gender) {
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
