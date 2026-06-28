package com.grahambartley.engine;

/**
 * Maps a backend-neutral voice request ({@code race}, {@code gender}, {@code player}) to a concrete
 * Kokoro speaker id.
 *
 * <p>This is the standalone-engine copy of the plugin's {@code VoiceManager} speaker matrix (only
 * the English {@code kokoro-multi-lang-v1_0} voices, ids 0-27). It is duplicated here on purpose:
 * the engine ships as its own self-contained runtime and cannot depend on the RuneLite-coupled
 * plugin classes. The ids must stay in lockstep with {@code VoiceManager.VoiceProfile} so a given
 * race/gender produces the same voice whether synthesis runs in-process (legacy) or through this
 * external engine.
 */
final class SpeakerMatrix {

  private SpeakerMatrix() {}

  // Player voices.
  private static final int PLAYER_MALE = 16; // am_michael
  private static final int PLAYER_FEMALE = 3; // af_heart

  // NPC race/gender voices.
  private static final int HUMAN_MALE = 14; // am_fenrir
  private static final int HUMAN_FEMALE = 2; // af_bella
  private static final int ELF_MALE = 26; // bm_george
  private static final int ELF_FEMALE = 21; // bf_emma
  private static final int DWARF_MALE = 27; // bm_lewis
  private static final int DWARF_FEMALE = 22; // bf_isabella
  private static final int GOBLIN_MALE = 18; // am_puck
  private static final int GOBLIN_FEMALE = 10; // af_sky
  private static final int MONKEY_MALE = 15; // am_liam
  private static final int MONKEY_FEMALE = 4; // af_jessica
  private static final int TROLL_MALE = 17; // am_onyx
  private static final int TROLL_FEMALE = 9; // af_sarah
  private static final int UNDEAD_MALE = 12; // am_echo
  private static final int UNDEAD_FEMALE = 6; // af_nicole
  private static final int DEMON_MALE = 24; // bm_daniel
  private static final int DEMON_FEMALE = 8; // af_river
  private static final int WIZARD_MALE = 25; // bm_fable
  private static final int WIZARD_FEMALE = 0; // af_alloy

  /**
   * Resolves the Kokoro speaker id for the given voice request. Unknown races collapse to the human
   * voice and unknown genders are treated as male, mirroring the plugin's fallback behaviour so the
   * engine never produces silence for an unexpected combination.
   */
  static int speakerId(boolean player, String race, String gender) {
    boolean female = gender != null && gender.trim().equalsIgnoreCase("FEMALE");
    if (player) {
      return female ? PLAYER_FEMALE : PLAYER_MALE;
    }
    String r = race == null ? "" : race.trim().toUpperCase();
    switch (r) {
      case "ELF":
        return female ? ELF_FEMALE : ELF_MALE;
      case "DWARF":
        return female ? DWARF_FEMALE : DWARF_MALE;
      case "GOBLIN":
        return female ? GOBLIN_FEMALE : GOBLIN_MALE;
      case "TROLL":
        return female ? TROLL_FEMALE : TROLL_MALE;
      case "UNDEAD":
        return female ? UNDEAD_FEMALE : UNDEAD_MALE;
      case "DEMON":
        return female ? DEMON_FEMALE : DEMON_MALE;
      case "WIZARD":
        return female ? WIZARD_FEMALE : WIZARD_MALE;
      case "MONKEY":
        return female ? MONKEY_FEMALE : MONKEY_MALE;
      case "HUMAN":
      default:
        return female ? HUMAN_FEMALE : HUMAN_MALE;
    }
  }
}
