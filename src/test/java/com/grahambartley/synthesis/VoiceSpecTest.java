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

  @Test
  public void bareNpcSpecHasNoExplicitSpeaker() {
    VoiceSpec spec = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE);
    assertFalse(spec.hasExplicitKokoroSpeakerId());
    assertEquals(VoiceSpec.UNSPECIFIED_SPEAKER_ID, spec.kokoroSpeakerId());
    assertEquals("npc:HUMAN:MALE", spec.key());
  }

  @Test
  public void explicitSpeakerIsCarriedAndFoldedIntoKey() {
    VoiceSpec spec = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE, 17);
    assertTrue(spec.hasExplicitKokoroSpeakerId());
    assertEquals(17, spec.kokoroSpeakerId());
    // The chosen speaker is folded into the cache key so two same-race/gender NPCs with different
    // voices never share a cached frame.
    assertEquals("npc:HUMAN:MALE#17", spec.key());
  }

  @Test
  public void sameRaceGenderDifferentSpeakerProducesDifferentKeys() {
    VoiceSpec a = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE, 14);
    VoiceSpec b = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE, 17);
    assertNotEquals(a.key(), b.key());
    assertNotEquals(a, b);
  }

  @Test
  public void negativeSpeakerIsNormalisedToUnspecified() {
    VoiceSpec spec = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE, -5);
    assertFalse(spec.hasExplicitKokoroSpeakerId());
    assertEquals("npc:HUMAN:MALE", spec.key());
  }
}
