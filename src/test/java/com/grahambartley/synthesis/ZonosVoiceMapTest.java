package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import org.junit.Test;

/** VoiceSpec -> Zonos reference-voice id mapping, including the default for unmapped specs. */
public class ZonosVoiceMapTest {

  private final ZonosVoiceMap map = new ZonosVoiceMap();

  @Test
  public void playerVoicesAreGenderDistinct() {
    String male = map.voiceFor(VoiceSpec.player(NPCGender.MALE));
    String female = map.voiceFor(VoiceSpec.player(NPCGender.FEMALE));
    assertEquals("player_male", male);
    assertEquals("player_female", female);
    assertNotEquals(male, female);
  }

  @Test
  public void npcRaceAndGenderResolveToDistinctVoices() {
    String elfFemale = map.voiceFor(VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE));
    String dwarfMale = map.voiceFor(VoiceSpec.npc(NPCRace.DWARF, NPCGender.MALE));
    assertEquals("elf_female", elfFemale);
    assertEquals("dwarf_male", dwarfMale);
    assertNotEquals(elfFemale, dwarfMale);
  }

  @Test
  public void humanMaleAndFemaleAreDistinct() {
    assertNotEquals(
        map.voiceFor(VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE)),
        map.voiceFor(VoiceSpec.npc(NPCRace.HUMAN, NPCGender.FEMALE)));
  }

  @Test
  public void unmappedRaceFallsBackToDefault() {
    // UNKNOWN is not in the table; it must resolve to the default rather than null.
    assertEquals(
        ZonosVoiceMap.DEFAULT_VOICE, map.voiceFor(VoiceSpec.npc(NPCRace.UNKNOWN, NPCGender.MALE)));
  }

  @Test
  public void nullSpecResolvesToDefault() {
    assertEquals(ZonosVoiceMap.DEFAULT_VOICE, map.voiceFor(null));
  }

  @Test
  public void unknownGenderStillResolvesToAConcreteVoice() {
    assertNotNull(map.voiceFor(VoiceSpec.npc(NPCRace.GOBLIN, NPCGender.UNKNOWN)));
  }
}
