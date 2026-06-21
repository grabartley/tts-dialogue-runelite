package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.NPCGender;
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
    private final boolean raceBased;
    private final boolean fallbacks;
    private final VoiceProfile playerVoice;

    TestConfig(boolean raceBased, boolean fallbacks, VoiceProfile playerVoice) {
      this.raceBased = raceBased;
      this.fallbacks = fallbacks;
      this.playerVoice = playerVoice;
    }

    @Override
    public boolean enableRaceBasedVoices() {
      return raceBased;
    }

    @Override
    public boolean enableFallbacks() {
      return fallbacks;
    }

    @Override
    public VoiceProfile playerVoice() {
      return playerVoice;
    }
  }

  private VoiceManager newManager(boolean raceBased, boolean fallbacks, VoiceProfile playerVoice) {
    // A null client means findNPCByName returns null, so NPC lookups exercise the fallback paths
    // without needing a live game world.
    return new VoiceManager(new TestConfig(raceBased, fallbacks, playerVoice), null);
  }

  @Test
  public void everyCategoryMapsToADistinctSpeaker() {
    Set<Integer> ids = new HashSet<>();
    for (VoiceProfile voice : VoiceProfile.values()) {
      ids.add(voice.getSpeakerId());
    }
    assertEquals("each race/gender category must map to a unique Kokoro speaker", 18, ids.size());
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
    VoiceManager manager = newManager(true, true, VoiceProfile.PLAYER_FEMALE);
    VoiceSpec spec = manager.resolveVoice("player", null);
    assertTrue("player voice should be a player spec", spec.player());
    assertEquals(NPCGender.FEMALE, spec.gender());
    assertEquals("player:FEMALE", spec.key());
  }

  @Test
  public void playerSpecMapsToConfiguredKokoroSpeaker() {
    VoiceManager manager = newManager(true, true, VoiceProfile.PLAYER_FEMALE);
    VoiceSpec spec = manager.resolveVoice("player", null);
    assertEquals(VoiceProfile.PLAYER_FEMALE.getSpeakerId(), manager.kokoroSpeakerId(spec));
  }

  @Test
  public void playerSpeakerMatchingIsCaseInsensitive() {
    VoiceManager manager = newManager(true, true, VoiceProfile.PLAYER_MALE);
    VoiceSpec spec = manager.resolveVoice("PLAYER", null);
    assertTrue(spec.player());
    assertEquals(VoiceProfile.PLAYER_MALE.getSpeakerId(), manager.kokoroSpeakerId(spec));
  }

  @Test
  public void npcUsesDefaultVoiceWhenAutomaticVoicesDisabled() {
    VoiceManager manager = newManager(false, true, VoiceProfile.PLAYER_MALE);
    VoiceSpec spec = manager.resolveVoice("npc", "Some Goblin");
    assertFalse("an NPC resolves to a non-player spec", spec.player());
    assertEquals("npc:HUMAN:MALE", spec.key());
    assertEquals(VoiceProfile.HUMAN_MALE.getSpeakerId(), manager.kokoroSpeakerId(spec));
  }

  @Test
  public void undetectedNpcWithFallbacksResolvesToHumanVoice() {
    VoiceManager manager = newManager(true, true, VoiceProfile.PLAYER_MALE);
    // No client, so the NPC can't be found and detection falls back.
    assertEquals(VoiceProfile.HUMAN_MALE, manager.getVoiceForNPC("Hans"));
    VoiceSpec spec = manager.resolveVoice("npc", "Hans");
    assertEquals("npc:HUMAN:MALE", spec.key());
    assertEquals(VoiceProfile.HUMAN_MALE.getSpeakerId(), manager.kokoroSpeakerId(spec));
  }

  @Test
  public void kokoroSpeakerIdRoundTripsEveryRaceGenderProfile() {
    VoiceManager manager = newManager(true, true, VoiceProfile.PLAYER_MALE);
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
  public void undetectedNpcWithFallbacksDisabledResolvesToDefaultVoice() {
    VoiceManager manager = newManager(true, false, VoiceProfile.PLAYER_MALE);
    assertEquals(VoiceProfile.HUMAN_MALE, manager.getVoiceForNPC("Hans"));
  }

  @Test
  public void blankNpcNameResolvesToFallbackVoice() {
    VoiceManager manager = newManager(true, true, VoiceProfile.PLAYER_MALE);
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
  public void buildNpcTraceShowsWorldHitTableSourceAndFinalVoice() {
    String trace =
        VoiceManager.buildNpcTrace(
            "Goblin",
            101,
            VoiceManager.NPCRace.GOBLIN,
            NPCGender.MALE,
            "table-hit",
            VoiceProfile.GOBLIN_MALE);
    assertTrue(trace, trace.contains("npc='Goblin'"));
    assertTrue(trace, trace.contains("world=HIT(id=101)"));
    assertTrue(trace, trace.contains("source=table-hit"));
    assertTrue(trace, trace.contains("voice=Goblin Male"));
    assertTrue(trace, trace.contains("speakerId=" + VoiceProfile.GOBLIN_MALE.getSpeakerId()));
  }

  @Test
  public void buildNpcTraceShowsWorldMissForUntabledNpc() {
    String trace =
        VoiceManager.buildNpcTrace(
            "Hans", null, null, NPCGender.UNKNOWN, "not-in-world", VoiceProfile.HUMAN_MALE);
    assertTrue(trace, trace.contains("world=MISS"));
    assertTrue(trace, trace.contains("race=UNKNOWN"));
    assertTrue(trace, trace.contains("voice=Human Male"));
  }

  @Test
  public void buildPlayerTraceNamesTheVoiceAndSpeaker() {
    String trace = VoiceManager.buildPlayerTrace(VoiceProfile.PLAYER_FEMALE);
    assertTrue(trace, trace.contains("player ->"));
    assertTrue(trace, trace.contains("voice=Player Female"));
    assertTrue(trace, trace.contains("speakerId=" + VoiceProfile.PLAYER_FEMALE.getSpeakerId()));
  }
}
