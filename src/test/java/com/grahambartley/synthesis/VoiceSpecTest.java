package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import org.junit.Test;

public class VoiceSpecTest {

  @Test
  public void playerKeyOmitsRace() {
    assertEquals("player:MALE", VoiceSpec.player(NPCGender.MALE).key());
    assertEquals("player:FEMALE", VoiceSpec.player(NPCGender.FEMALE).key());
  }

  @Test
  public void npcKeyCarriesRaceAndGender() {
    assertEquals("npc:ELF:FEMALE", VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE).key());
    assertEquals("npc:DEMON:MALE", VoiceSpec.npc(NPCRace.DEMON, NPCGender.MALE).key());
  }

  @Test
  public void playerSpecIsFlaggedAsPlayer() {
    assertTrue(VoiceSpec.player(NPCGender.MALE).player());
    assertFalse(VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE).player());
  }

  @Test
  public void equalSpecsShareKeyAndEquality() {
    VoiceSpec a = VoiceSpec.npc(NPCRace.GOBLIN, NPCGender.FEMALE);
    VoiceSpec b = VoiceSpec.npc(NPCRace.GOBLIN, NPCGender.FEMALE);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertEquals(a.key(), b.key());
  }

  @Test
  public void playerAndNpcOfSameGenderAreDistinct() {
    VoiceSpec player = VoiceSpec.player(NPCGender.MALE);
    VoiceSpec npc = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE);
    assertNotEquals(player, npc);
    assertNotEquals(player.key(), npc.key());
  }
}
