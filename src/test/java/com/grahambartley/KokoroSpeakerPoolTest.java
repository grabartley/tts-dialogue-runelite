package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.KokoroVoice;
import com.grahambartley.VoiceManager.NPCGender;
import org.junit.Test;

/** Gender-segregated British speaker pools and the stable per-NPC speaker hashing (#78). */
public class KokoroSpeakerPoolTest {

  @Test
  public void genderPoolsAreNonEmptyDisjointBritishAndGenderCorrect() {
    assertTrue("male pool should not be empty", KokoroSpeakerPool.MALE_SPEAKER_POOL.length > 0);
    assertTrue("female pool should not be empty", KokoroSpeakerPool.FEMALE_SPEAKER_POOL.length > 0);
    for (int id : KokoroSpeakerPool.MALE_SPEAKER_POOL) {
      assertEquals("male pool id " + id + " must be a male voice", NPCGender.MALE, genderOf(id));
      assertTrue("male pool id " + id + " must be British", isBritish(id));
    }
    for (int id : KokoroSpeakerPool.FEMALE_SPEAKER_POOL) {
      assertEquals(
          "female pool id " + id + " must be a female voice", NPCGender.FEMALE, genderOf(id));
      assertTrue("female pool id " + id + " must be British", isBritish(id));
    }
    for (int m : KokoroSpeakerPool.MALE_SPEAKER_POOL) {
      assertFalse("pools must be disjoint", contains(KokoroSpeakerPool.FEMALE_SPEAKER_POOL, m));
    }
  }

  @Test
  public void playerSpeakerIsBritishAndGenderCorrect() {
    assertEquals(NPCGender.MALE, genderOf(KokoroSpeakerPool.playerSpeaker(NPCGender.MALE)));
    assertEquals(NPCGender.FEMALE, genderOf(KokoroSpeakerPool.playerSpeaker(NPCGender.FEMALE)));
  }

  @Test
  public void selectionIsByGenderNotRace() {
    // Same gender and key always hash to the same British voice; race does not influence Local.
    int a = KokoroSpeakerPool.pickNpcSpeakerId(NPCGender.MALE, 4242, "Guard");
    int b = KokoroSpeakerPool.pickNpcSpeakerId(NPCGender.MALE, 4242, "Guard");
    assertEquals(a, b);
  }

  @Test
  public void differentNpcIdsOfSameGenderGetDifferentSpeakers() {
    assertTrue("pool must offer variety", KokoroSpeakerPool.MALE_SPEAKER_POOL.length > 1);
    int a = KokoroSpeakerPool.pickNpcSpeakerId(NPCGender.MALE, 1001, "Guard");
    int b = KokoroSpeakerPool.pickNpcSpeakerId(NPCGender.MALE, 1002, "Guard");
    assertNotEquals("adjacent ids must land in different slots", a, b);
    assertEquals(NPCGender.MALE, genderOf(a));
    assertEquals(NPCGender.MALE, genderOf(b));
  }

  @Test
  public void perNpcSpeakerIsStableAcrossCalls() {
    int first = KokoroSpeakerPool.pickNpcSpeakerId(NPCGender.MALE, 4242, "Guard");
    for (int i = 0; i < 5; i++) {
      assertEquals(first, KokoroSpeakerPool.pickNpcSpeakerId(NPCGender.MALE, 4242, "Guard"));
    }
  }

  @Test
  public void perNpcSelectionKeysOnNameWhenNoCompositionId() {
    int hans = KokoroSpeakerPool.pickNpcSpeakerId(NPCGender.MALE, null, "Hans");
    int hansAgain =
        KokoroSpeakerPool.pickNpcSpeakerId(NPCGender.MALE, null, "<col=ff0000>Hans</col>");
    assertEquals("normalised name keys identically", hans, hansAgain);
    assertEquals(NPCGender.MALE, genderOf(hans));
  }

  @Test
  public void femaleNpcOnlyEverGetsFemaleSpeaker() {
    for (int id = 0; id < 200; id++) {
      int chosen = KokoroSpeakerPool.pickNpcSpeakerId(NPCGender.FEMALE, id, "Woman");
      assertEquals(
          "female NPC id " + id + " must map to a female voice",
          NPCGender.FEMALE,
          genderOf(chosen));
    }
  }

  private static NPCGender genderOf(int speakerId) {
    for (KokoroVoice voice : KokoroVoice.values()) {
      if (voice.getSpeakerId() == speakerId) {
        return voice.getGender();
      }
    }
    return NPCGender.UNKNOWN;
  }

  private static boolean isBritish(int speakerId) {
    return KokoroVoice.nameFor(speakerId).startsWith("b");
  }

  private static boolean contains(int[] pool, int value) {
    for (int v : pool) {
      if (v == value) {
        return true;
      }
    }
    return false;
  }
}
