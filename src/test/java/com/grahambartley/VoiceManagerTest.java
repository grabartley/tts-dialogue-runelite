package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.VoiceProfile;
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
  public void playerUsesConfiguredVoice() {
    VoiceManager manager = newManager(true, true, VoiceProfile.PLAYER_FEMALE);
    assertEquals(VoiceProfile.PLAYER_FEMALE.getSpeakerId(), manager.getSpeakerId("player", null));
  }

  @Test
  public void playerSpeakerMatchingIsCaseInsensitive() {
    VoiceManager manager = newManager(true, true, VoiceProfile.PLAYER_MALE);
    assertEquals(VoiceProfile.PLAYER_MALE.getSpeakerId(), manager.getSpeakerId("PLAYER", null));
  }

  @Test
  public void npcUsesDefaultVoiceWhenAutomaticVoicesDisabled() {
    VoiceManager manager = newManager(false, true, VoiceProfile.PLAYER_MALE);
    assertEquals(
        VoiceProfile.HUMAN_MALE.getSpeakerId(), manager.getSpeakerId("npc", "Some Goblin"));
  }

  @Test
  public void undetectedNpcWithFallbacksResolvesToHumanVoice() {
    VoiceManager manager = newManager(true, true, VoiceProfile.PLAYER_MALE);
    // No client, so the NPC can't be found and detection falls back.
    assertEquals(VoiceProfile.HUMAN_MALE, manager.getVoiceForNPC("Hans"));
    assertEquals(VoiceProfile.HUMAN_MALE.getSpeakerId(), manager.getSpeakerId("npc", "Hans"));
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
}
