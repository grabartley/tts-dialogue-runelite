package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Gender-correctness, determinism, and per-NPC spread of the Gemini race/gender voice map. */
public class GeminiVoiceMapTest {

  private final GeminiVoiceMap map = new GeminiVoiceMap();

  /** Races that carry a real race/gender mapping (UNKNOWN intentionally falls back). */
  private static final NPCRace[] MAPPED_RACES = {
    NPCRace.HUMAN,
    NPCRace.ELF,
    NPCRace.DWARF,
    NPCRace.GOBLIN,
    NPCRace.MONKEY,
    NPCRace.GORILLA,
    NPCRace.TROLL,
    NPCRace.UNDEAD,
    NPCRace.DEMON,
    NPCRace.WIZARD,
    NPCRace.TORTUGAN
  };

  /**
   * The 30 prebuilt Gemini TTS voices. Every voice the map can emit must be one of these names; a
   * name that is not a real voice fails synthesis at runtime, so the map is checked against the
   * catalog here rather than only by ear.
   */
  private static final Set<String> GEMINI_VOICE_CATALOG =
      new HashSet<>(
          java.util.Arrays.asList(
              "Zephyr",
              "Puck",
              "Charon",
              "Kore",
              "Fenrir",
              "Leda",
              "Orus",
              "Aoede",
              "Callirrhoe",
              "Autonoe",
              "Enceladus",
              "Iapetus",
              "Umbriel",
              "Algieba",
              "Despina",
              "Erinome",
              "Algenib",
              "Rasalgethi",
              "Laomedeia",
              "Achernar",
              "Alnilam",
              "Schedar",
              "Gacrux",
              "Pulcherrima",
              "Achird",
              "Zubenelgenubi",
              "Vindemiatrix",
              "Sadachbia",
              "Sadaltager",
              "Sulafat"));

  @Test
  public void everyRaceGenderPairResolvesToANonBlankVoice() {
    for (NPCRace race : NPCRace.values()) {
      for (NPCGender gender :
          new NPCGender[] {NPCGender.MALE, NPCGender.FEMALE, NPCGender.UNKNOWN}) {
        String voice = map.voiceFor(VoiceSpec.npc(race, gender));
        assertNotNull(race + "/" + gender + " resolves", voice);
        assertFalse(race + "/" + gender + " is non-blank", voice.trim().isEmpty());
      }
    }
  }

  @Test
  public void maleAndFemaleVoicePoolsAreDisjointAcrossEveryRace() {
    Set<String> male = voicesFor(NPCGender.MALE);
    Set<String> female = voicesFor(NPCGender.FEMALE);

    Set<String> overlap = new HashSet<>(male);
    overlap.retainAll(female);
    assertTrue(
        "no voice is shared between genders, so no race voices two genders alike: " + overlap,
        overlap.isEmpty());
  }

  /**
   * Every voice any spec of {@code gender} can produce, sweeping races and a spread of NPC seeds.
   */
  private Set<String> voicesFor(NPCGender gender) {
    Set<String> voices = new HashSet<>();
    for (NPCRace race : MAPPED_RACES) {
      for (int seed = 0; seed < 64; seed++) {
        voices.add(map.voiceFor(VoiceSpec.npc(race, gender, seed)));
      }
    }
    voices.add(map.voiceFor(VoiceSpec.player(gender)));
    return voices;
  }

  @Test
  public void sameSpecIsStableAcrossCalls() {
    VoiceSpec spec = VoiceSpec.npc(NPCRace.DWARF, NPCGender.MALE, 4242);
    assertEquals("a given NPC always voices the same way", map.voiceFor(spec), map.voiceFor(spec));
  }

  @Test
  public void sameRaceGenderDifferentNpcsCanGetDifferentVoices() {
    Set<String> seen = new HashSet<>();
    for (int seed = 0; seed < 16; seed++) {
      seen.add(map.voiceFor(VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE, seed)));
    }
    assertTrue("the per-NPC seed spreads across the sub-pool", seen.size() > 1);
  }

  @Test
  public void unknownRaceFallsBackInsteadOfThrowing() {
    String male = map.voiceFor(VoiceSpec.npc(NPCRace.UNKNOWN, NPCGender.MALE, 7));
    String female = map.voiceFor(VoiceSpec.npc(NPCRace.UNKNOWN, NPCGender.FEMALE, 7));
    assertNotNull(male);
    assertNotNull(female);
  }

  @Test
  public void playerVoiceRespectsGenderAndStaysGenderCorrect() {
    String playerMale = map.voiceFor(VoiceSpec.player(NPCGender.MALE));
    String playerFemale = map.voiceFor(VoiceSpec.player(NPCGender.FEMALE));
    assertFalse("player male and female differ", playerMale.equals(playerFemale));
    assertTrue("player male is in the male pool", voicesFor(NPCGender.MALE).contains(playerMale));
    assertTrue(
        "player female is in the female pool", voicesFor(NPCGender.FEMALE).contains(playerFemale));
  }

  @Test
  public void everyEmittableVoiceIsARealGeminiVoice() {
    Set<String> emitted = new HashSet<>();
    emitted.addAll(voicesFor(NPCGender.MALE));
    emitted.addAll(voicesFor(NPCGender.FEMALE));
    emitted.add(GeminiVoiceMap.DEFAULT_VOICE);
    Set<String> bogus = new HashSet<>(emitted);
    bogus.removeAll(GEMINI_VOICE_CATALOG);
    assertTrue(
        "every mapped voice must be a real Gemini voice, not these: " + bogus, bogus.isEmpty());
  }

  @Test
  public void nullSpecResolvesToTheDefaultVoice() {
    assertEquals(GeminiVoiceMap.DEFAULT_VOICE, map.voiceFor(null));
  }
}
