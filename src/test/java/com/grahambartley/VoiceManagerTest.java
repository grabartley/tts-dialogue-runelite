package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.VoiceManager.PlayerVoice;
import com.grahambartley.VoiceManager.VoiceProfile;
import com.grahambartley.synthesis.VoiceSpec;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class VoiceManagerTest {

  /**
   * Minimal config used to drive voice resolution without the RuneLite client. Only the toggles the
   * mapping reads are overridden; everything else keeps its interface default.
   */
  private static final class TestConfig implements TTSDialogueConfig {
    private final PlayerVoice playerVoice;

    TestConfig(PlayerVoice playerVoice) {
      this.playerVoice = playerVoice;
    }

    @Override
    public PlayerVoice playerVoice() {
      return playerVoice;
    }
  }

  private VoiceManager newManager(PlayerVoice playerVoice) {
    // A null client means findNPCByName returns null, so NPC lookups exercise the default-voice
    // path without needing a live game world.
    return new VoiceManager(new TestConfig(playerVoice), null);
  }

  @Test
  public void everyCategoryMapsToADistinctSpeaker() {
    Set<Integer> ids = new HashSet<>();
    for (VoiceProfile voice : VoiceProfile.values()) {
      ids.add(voice.getSpeakerId());
    }
    assertEquals("each race/gender category must map to a unique Kokoro speaker", 24, ids.size());
    assertEquals(VoiceProfile.values().length, ids.size());
  }

  @Test
  public void speakerIdsStayWithinEnglishVoiceBank() {
    // The kokoro-multi-lang-v1_0 English voices (af_/am_/bf_/bm_) occupy speaker ids 0-27.
    for (VoiceProfile voice : VoiceProfile.values()) {
      int id = voice.getSpeakerId();
      assertTrue(voice + " id out of English range: " + id, id >= 0 && id <= 27);
    }
  }

  @Test
  public void everyProfileNamesItsKokoroVoice() {
    for (VoiceProfile voice : VoiceProfile.values()) {
      assertTrue(voice + " missing kokoro voice name", voice.getKokoroVoice().length() > 0);
    }
    // Pin a few documented pairings so the matrix can't drift silently.
    assertEquals("am_michael", VoiceProfile.PLAYER_MALE.getKokoroVoice());
    assertEquals("af_heart", VoiceProfile.PLAYER_FEMALE.getKokoroVoice());
    assertEquals("bm_george", VoiceProfile.ELF_MALE.getKokoroVoice());
    assertEquals("bm_fable", VoiceProfile.WIZARD_MALE.getKokoroVoice());
  }

  @Test
  public void playerResolvesToPlayerSpecWithConfiguredGender() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_B);
    VoiceSpec spec = manager.resolveVoice("player", null);
    assertTrue("player voice should be a player spec", spec.player());
    assertEquals(NPCGender.FEMALE, spec.gender());
    assertEquals("player:FEMALE", spec.key());
  }

  @Test
  public void playerSpecMapsToConfiguredKokoroSpeaker() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_B);
    VoiceSpec spec = manager.resolveVoice("player", null);
    assertEquals(VoiceProfile.PLAYER_FEMALE.getSpeakerId(), manager.kokoroSpeakerId(spec));
  }

  @Test
  public void playerSpeakerMatchingIsCaseInsensitive() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    VoiceSpec spec = manager.resolveVoice("PLAYER", null);
    assertTrue(spec.player());
    assertEquals(VoiceProfile.PLAYER_MALE.getSpeakerId(), manager.kokoroSpeakerId(spec));
  }

  @Test
  public void playerVoiceTypesMapToHumanPlayerSpeakersAndGender() {
    assertEquals(VoiceProfile.PLAYER_MALE, PlayerVoice.TYPE_A.getVoiceProfile());
    assertEquals(NPCGender.MALE, PlayerVoice.TYPE_A.getVoiceProfile().getGender());
    assertEquals(VoiceProfile.PLAYER_FEMALE, PlayerVoice.TYPE_B.getVoiceProfile());
    assertEquals(NPCGender.FEMALE, PlayerVoice.TYPE_B.getVoiceProfile().getGender());
  }

  @Test
  public void undetectedNpcResolvesToTheDefaultVoice() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    // No client, so the NPC can't be found and detection resolves to the single default voice.
    assertEquals(VoiceProfile.HUMAN_MALE, manager.getVoiceForNPC("Hans"));
    VoiceSpec spec = manager.resolveVoice("npc", "Hans");
    // Still a human-male spec, but per-NPC variety (issue #78) stamps a stable male-pool speaker
    // keyed off the name (no composition id without a client), so the speaker is gender-correct and
    // the cache key carries it.
    assertEquals(NPCRace.HUMAN, spec.race());
    assertEquals(NPCGender.MALE, spec.gender());
    assertTrue("default-voice NPC still gets a per-NPC speaker", spec.hasExplicitKokoroSpeakerId());
    assertTrue(
        "chosen speaker must be from the male pool",
        contains(VoiceManager.MALE_SPEAKER_POOL, manager.kokoroSpeakerId(spec)));
    assertTrue("cache key carries the chosen speaker", spec.key().startsWith("npc:HUMAN:MALE#"));
  }

  @Test
  public void kokoroSpeakerIdRoundTripsEveryRaceGenderProfile() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    for (VoiceProfile profile : VoiceProfile.values()) {
      if (profile == VoiceProfile.PLAYER_MALE || profile == VoiceProfile.PLAYER_FEMALE) {
        continue; // Player profiles share HUMAN race; covered by the player-spec tests.
      }
      VoiceSpec spec = VoiceSpec.npc(profile.getRace(), profile.getGender());
      assertEquals(
          profile + " should round-trip to its own speaker id",
          profile.getSpeakerId(),
          manager.kokoroSpeakerId(spec));
    }
  }

  @Test
  public void gorillaRaceResolvesToTheDeepGorillaSpeakers() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    assertEquals("am_adam", VoiceProfile.GORILLA_MALE.getKokoroVoice());
    assertEquals("bf_alice", VoiceProfile.GORILLA_FEMALE.getKokoroVoice());
    assertEquals(
        VoiceProfile.GORILLA_MALE.getSpeakerId(),
        manager.kokoroSpeakerId(VoiceSpec.npc(NPCRace.GORILLA, NPCGender.MALE)));
    assertEquals(
        VoiceProfile.GORILLA_FEMALE.getSpeakerId(),
        manager.kokoroSpeakerId(VoiceSpec.npc(NPCRace.GORILLA, NPCGender.FEMALE)));
    // A gorilla must not collapse onto the chattery monkey speaker.
    assertNotEquals(
        VoiceProfile.MONKEY_MALE.getSpeakerId(), VoiceProfile.GORILLA_MALE.getSpeakerId());
  }

  @Test
  public void blankNpcNameResolvesToDefaultVoice() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    assertEquals(VoiceProfile.HUMAN_MALE, manager.getVoiceForNPC(""));
    assertEquals(VoiceProfile.HUMAN_MALE, manager.getVoiceForNPC(null));
  }

  @Test
  public void normalizeNameStripsColorTagsTrimsAndNormalisesNbsp() {
    // Dialogue name widget can carry colour markup, non-breaking spaces, and stray whitespace; the
    // world composition name does not. Both must normalise to the same string so matching succeeds.
    assertEquals("Hans", VoiceManager.normalizeName("<col=0000ff>Hans</col>"));
    assertEquals("Hans", VoiceManager.normalizeName("  Hans  "));
    assertEquals("Father Aereck", VoiceManager.normalizeName("Father Aereck"));
    assertEquals("", VoiceManager.normalizeName(null));
    assertEquals("", VoiceManager.normalizeName("<col=ff0000></col>"));
  }

  @Test
  public void buildNpcTraceShowsWorldHitTableSourceAndChosenSpeaker() {
    // The chosen speaker (am_onyx, id 17) is a male-pool voice distinct from the Goblin Male
    // baseline, proving the trace logs the actual per-NPC speaker, not just the baseline id.
    String trace =
        VoiceManager.buildNpcTrace(
            "Goblin",
            101,
            VoiceManager.NPCRace.GOBLIN,
            NPCGender.MALE,
            "table-hit",
            VoiceProfile.GOBLIN_MALE,
            17);
    assertTrue(trace, trace.contains("npc='Goblin'"));
    assertTrue(trace, trace.contains("world=HIT(id=101)"));
    assertTrue(trace, trace.contains("source=table-hit"));
    assertTrue(trace, trace.contains("voice=Goblin Male"));
    // The actual chosen Kokoro speaker id + its friendly name are surfaced for QA.
    assertTrue(trace, trace.contains("speakerId=17"));
    assertTrue(trace, trace.contains("am_onyx"));
  }

  @Test
  public void buildNpcTraceShowsWorldMissForUntabledNpc() {
    String trace =
        VoiceManager.buildNpcTrace(
            "Hans",
            null,
            null,
            NPCGender.UNKNOWN,
            "not-in-world",
            VoiceProfile.HUMAN_MALE,
            VoiceProfile.HUMAN_MALE.getSpeakerId());
    assertTrue(trace, trace.contains("world=MISS"));
    assertTrue(trace, trace.contains("race=UNKNOWN"));
    assertTrue(trace, trace.contains("voice=Human Male"));
  }

  // ---- Per-NPC voice variety (issue #78) ----

  @Test
  public void genderPoolsAreNonEmptyDisjointAndGenderCorrect() {
    assertTrue("male pool should not be empty", VoiceManager.MALE_SPEAKER_POOL.length > 0);
    assertTrue("female pool should not be empty", VoiceManager.FEMALE_SPEAKER_POOL.length > 0);
    // Every male-pool id is a male VoiceProfile (am_/bm_); every female-pool id a female (af_/bf_).
    for (int id : VoiceManager.MALE_SPEAKER_POOL) {
      assertEquals(
          "male pool id " + id + " must name a male voice", NPCGender.MALE, genderOfSpeaker(id));
    }
    for (int id : VoiceManager.FEMALE_SPEAKER_POOL) {
      assertEquals(
          "female pool id " + id + " must name a female voice",
          NPCGender.FEMALE,
          genderOfSpeaker(id));
    }
    // The pools never overlap, so a male NPC can never collide with a female voice and vice versa.
    for (int m : VoiceManager.MALE_SPEAKER_POOL) {
      assertFalse("pools must be disjoint", contains(VoiceManager.FEMALE_SPEAKER_POOL, m));
    }
    // Player-only voices (am_michael=16, af_heart=3) must not leak into the NPC pools.
    assertFalse(contains(VoiceManager.MALE_SPEAKER_POOL, VoiceProfile.PLAYER_MALE.getSpeakerId()));
    assertFalse(
        contains(VoiceManager.FEMALE_SPEAKER_POOL, VoiceProfile.PLAYER_FEMALE.getSpeakerId()));
  }

  @Test
  public void differentNpcIdsOfSameRaceGenderGetDifferentSpeakers() {
    // Two human-male NPCs with different composition ids must not collapse to one voice. The pool
    // is sorted, so two ids landing in different slots prove variety. Ids chosen to differ mod
    // pool.
    int poolSize = VoiceManager.MALE_SPEAKER_POOL.length;
    int idA = 1001;
    int idB = 1001 + 1; // adjacent ids land in adjacent (distinct) slots for any pool size > 1.
    int a = VoiceManager.pickNpcSpeakerId(VoiceProfile.HUMAN_MALE, idA, "Guard");
    int b = VoiceManager.pickNpcSpeakerId(VoiceProfile.HUMAN_MALE, idB, "Guard");
    assertTrue("pool must offer variety", poolSize > 1);
    assertNotEquals("different NPC ids must get different speakers", a, b);
    // Both are still gender-correct male voices.
    assertEquals(NPCGender.MALE, genderOfSpeaker(a));
    assertEquals(NPCGender.MALE, genderOfSpeaker(b));
  }

  @Test
  public void perNpcSpeakerIsStableAcrossCalls() {
    int first = VoiceManager.pickNpcSpeakerId(VoiceProfile.HUMAN_MALE, 4242, "Guard");
    for (int i = 0; i < 5; i++) {
      assertEquals(
          "same NPC must always resolve to the same speaker",
          first,
          VoiceManager.pickNpcSpeakerId(VoiceProfile.HUMAN_MALE, 4242, "Guard"));
    }
  }

  @Test
  public void perNpcSelectionKeysOnNameWhenNoCompositionId() {
    // Without a composition id the normalised name is the key, so two differently named NPCs can
    // still diverge while the same name is stable.
    int hans = VoiceManager.pickNpcSpeakerId(VoiceProfile.HUMAN_MALE, null, "Hans");
    int hansAgain =
        VoiceManager.pickNpcSpeakerId(VoiceProfile.HUMAN_MALE, null, "<col=ff0000>Hans</col>");
    assertEquals("normalised name keys identically", hans, hansAgain);
    assertEquals(NPCGender.MALE, genderOfSpeaker(hans));
  }

  @Test
  public void femaleNpcOnlyEverGetsFemaleSpeaker() {
    for (int id = 0; id < 200; id++) {
      int chosen = VoiceManager.pickNpcSpeakerId(VoiceProfile.HUMAN_FEMALE, id, "Woman");
      assertEquals(
          "female NPC id " + id + " must map to a female voice",
          NPCGender.FEMALE,
          genderOfSpeaker(chosen));
    }
  }

  @Test
  public void playerPathIgnoresPerNpcSpeakerAndIsUnchanged() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    VoiceSpec spec = manager.resolveVoice("player", "ignored-name");
    assertTrue(spec.player());
    // No per-NPC speaker is ever stamped on the player; it maps to the configured player voice.
    assertFalse(
        "player spec carries no explicit per-NPC speaker", spec.hasExplicitKokoroSpeakerId());
    assertEquals("player:MALE", spec.key());
    assertEquals(VoiceProfile.PLAYER_MALE.getSpeakerId(), manager.kokoroSpeakerId(spec));
  }

  @Test
  public void explicitSpeakerOnSpecWinsInKokoroSpeakerId() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    // An NPC spec stamped with an explicit speaker is honored verbatim, not recomputed from race.
    VoiceSpec spec = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE, 17);
    assertEquals(17, manager.kokoroSpeakerId(spec));
    // A bare spec still falls back to the race/gender matrix.
    VoiceSpec bare = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE);
    assertEquals(VoiceProfile.HUMAN_MALE.getSpeakerId(), manager.kokoroSpeakerId(bare));
  }

  private static NPCGender genderOfSpeaker(int speakerId) {
    for (VoiceProfile profile : VoiceProfile.values()) {
      if (profile.getSpeakerId() == speakerId) {
        return profile.getGender();
      }
    }
    return NPCGender.UNKNOWN;
  }

  private static boolean contains(int[] pool, int value) {
    for (int v : pool) {
      if (v == value) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void buildPlayerTraceNamesTheVoiceAndSpeaker() {
    String trace = VoiceManager.buildPlayerTrace(VoiceProfile.PLAYER_FEMALE);
    assertTrue(trace, trace.contains("player ->"));
    assertTrue(trace, trace.contains("voice=Player Female"));
    assertTrue(trace, trace.contains("speakerId=" + VoiceProfile.PLAYER_FEMALE.getSpeakerId()));
  }
}
