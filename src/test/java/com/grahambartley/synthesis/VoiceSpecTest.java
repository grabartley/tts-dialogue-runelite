package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class VoiceSpecTest {

  @Test
  @Parameters(method = "playerKeyCases")
  public void playerKeyOmitsRace(NPCGender gender, String expected) {
    assertEquals(expected, VoiceSpec.player(gender).key());
  }

  private Object[] playerKeyCases() {
    return new Object[] {
      new Object[] {NPCGender.MALE, "player:MALE"},
      new Object[] {NPCGender.FEMALE, "player:FEMALE"},
    };
  }

  @Test
  @Parameters(method = "npcKeyCases")
  public void npcKeyCarriesRaceAndGender(NPCRace race, NPCGender gender, String expected) {
    assertEquals(expected, VoiceSpec.npc(race, gender).key());
  }

  private Object[] npcKeyCases() {
    return new Object[] {
      new Object[] {NPCRace.ELF, NPCGender.FEMALE, "npc:ELF:FEMALE"},
      new Object[] {NPCRace.DEMON, NPCGender.MALE, "npc:DEMON:MALE"},
    };
  }

  @Test
  @Parameters(method = "playerFlagCases")
  public void playerSpecIsFlaggedAsPlayer(VoiceSpec spec, boolean expected) {
    assertEquals(expected, spec.player());
  }

  private Object[] playerFlagCases() {
    return new Object[] {
      new Object[] {VoiceSpec.player(NPCGender.MALE), true},
      new Object[] {VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), false},
    };
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
