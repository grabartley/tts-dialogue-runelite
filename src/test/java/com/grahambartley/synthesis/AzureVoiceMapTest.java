package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import org.junit.Test;

/** VoiceSpec -> Azure neural voice name mapping, including defaults for unmapped specs. */
public class AzureVoiceMapTest {

  private final AzureVoiceMap map = new AzureVoiceMap();

  @Test
  public void playerVoicesAreGenderDistinct() {
    String male = map.voiceFor(VoiceSpec.player(NPCGender.MALE));
    String female = map.voiceFor(VoiceSpec.player(NPCGender.FEMALE));
    assertEquals("en-US-GuyNeural", male);
    assertEquals("en-US-JaneNeural", female);
    assertNotEquals(male, female);
  }

  @Test
  public void elfFemaleMapsToBritishVoice() {
    assertEquals("en-GB-SoniaNeural", map.voiceFor(VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE)));
  }

  @Test
  public void dwarfMaleMapsToDeepBritishVoice() {
    assertEquals("en-GB-ThomasNeural", map.voiceFor(VoiceSpec.npc(NPCRace.DWARF, NPCGender.MALE)));
  }

  @Test
  public void humanMaleAndFemaleAreDistinct() {
    String male = map.voiceFor(VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE));
    String female = map.voiceFor(VoiceSpec.npc(NPCRace.HUMAN, NPCGender.FEMALE));
    assertNotEquals(male, female);
  }

  @Test
  public void unknownRaceFallsBackToDefaultVoice() {
    assertEquals(
        AzureVoiceMap.DEFAULT_VOICE, map.voiceFor(VoiceSpec.npc(NPCRace.UNKNOWN, NPCGender.MALE)));
  }

  @Test
  public void unknownGenderStillResolvesToAConcreteVoice() {
    String voice = map.voiceFor(VoiceSpec.npc(NPCRace.HUMAN, NPCGender.UNKNOWN));
    assertEquals("unknown gender uses the male slot", "en-US-DavisNeural", voice);
  }

  @Test
  public void nullSpecYieldsDefault() {
    assertEquals(AzureVoiceMap.DEFAULT_VOICE, map.voiceFor(null));
  }

  @Test
  public void distinctRacesGetDistinctVoices() {
    String elf = map.voiceFor(VoiceSpec.npc(NPCRace.ELF, NPCGender.MALE));
    String troll = map.voiceFor(VoiceSpec.npc(NPCRace.TROLL, NPCGender.MALE));
    String goblin = map.voiceFor(VoiceSpec.npc(NPCRace.GOBLIN, NPCGender.MALE));
    assertNotEquals(elf, troll);
    assertNotEquals(elf, goblin);
    assertNotEquals(troll, goblin);
  }
}
