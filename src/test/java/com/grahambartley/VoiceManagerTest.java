package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.KokoroVoice;
import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.VoiceManager.PlayerVoice;
import com.grahambartley.synthesis.VoiceSpec;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * The {@link VoiceManager} facade: the British {@link KokoroVoice} bank, player voice resolution,
 * and the {@code kokoroSpeakerId} mapping. Speaker-pool hashing, trace formatting, name
 * normalisation, demographic parsing, NPC lookup, and NPC voice resolution have their own tests.
 */
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
    // A null client means the NPC is never found, so NPC lookups exercise the default-voice path
    // without needing a live game world.
    return new VoiceManager(new TestConfig(playerVoice), null);
  }

  // ---- British voice bank ----

  @Test
  public void bankIsBritishOnlyWithDistinctInRangeIds() {
    Set<Integer> ids = new HashSet<>();
    for (KokoroVoice voice : KokoroVoice.values()) {
      assertTrue(
          voice + " must be a British (bm_/bf_) voice", voice.getVoiceName().startsWith("b"));
      // The kokoro-multi-lang-v1_0 English voices occupy speaker ids 0-27.
      int id = voice.getSpeakerId();
      assertTrue(voice + " id out of range: " + id, id >= 0 && id <= 27);
      assertTrue(voice + " has a duplicate speaker id", ids.add(id));
    }
  }

  @Test
  public void everyVoiceNamesItsKokoroVoice() {
    for (KokoroVoice voice : KokoroVoice.values()) {
      assertTrue(voice + " missing kokoro voice name", voice.getVoiceName().length() > 0);
    }
    assertEquals("bm_george", KokoroVoice.BM_GEORGE.getVoiceName());
    assertEquals("bf_emma", KokoroVoice.BF_EMMA.getVoiceName());
  }

  @Test
  public void playerVoiceTypesFixGender() {
    assertEquals(NPCGender.MALE, PlayerVoice.TYPE_A.getGender());
    assertEquals(NPCGender.FEMALE, PlayerVoice.TYPE_B.getGender());
  }

  // ---- Player resolution ----

  @Test
  public void playerResolvesToPlayerSpecWithConfiguredGender() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_B);
    VoiceSpec spec = manager.resolveVoice("player", null);
    assertTrue("player voice should be a player spec", spec.player());
    assertEquals(NPCGender.FEMALE, spec.gender());
    assertEquals("player:FEMALE#" + KokoroSpeakerPool.playerSpeaker(NPCGender.FEMALE), spec.key());
  }

  @Test
  public void playerResolvesToABritishSpeakerByGender() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_B);
    VoiceSpec spec = manager.resolveVoice("player", null);
    assertTrue("player carries a British Local speaker", spec.hasExplicitKokoroSpeakerId());
    assertEquals(KokoroSpeakerPool.playerSpeaker(NPCGender.FEMALE), manager.kokoroSpeakerId(spec));
    assertTrue("player speaker must be British", isBritish(manager.kokoroSpeakerId(spec)));
  }

  @Test
  public void playerSpeakerMatchingIsCaseInsensitive() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    VoiceSpec spec = manager.resolveVoice("PLAYER", null);
    assertTrue(spec.player());
    assertEquals(KokoroSpeakerPool.playerSpeaker(NPCGender.MALE), manager.kokoroSpeakerId(spec));
  }

  // ---- kokoroSpeakerId mapping ----

  @Test
  public void undetectedNpcResolvesToTheDefaultHumanMaleVoice() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    // No client, so the NPC can't be found and detection resolves to the default human-male voice.
    VoiceSpec spec = manager.resolveVoice("npc", "Hans");
    assertEquals(NPCRace.HUMAN, spec.race());
    assertEquals(NPCGender.MALE, spec.gender());
    assertTrue("default-voice NPC still gets a per-NPC speaker", spec.hasExplicitKokoroSpeakerId());
    assertTrue(
        "chosen speaker must be from the male British pool",
        contains(KokoroSpeakerPool.MALE_SPEAKER_POOL, manager.kokoroSpeakerId(spec)));
    assertTrue("cache key carries the chosen speaker", spec.key().startsWith("npc:HUMAN:MALE#"));
  }

  @Test
  public void bareSpecsLandInTheGenderCorrectBritishPool() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    int male = manager.kokoroSpeakerId(VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE));
    int female = manager.kokoroSpeakerId(VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE));
    assertTrue(contains(KokoroSpeakerPool.MALE_SPEAKER_POOL, male));
    assertTrue(contains(KokoroSpeakerPool.FEMALE_SPEAKER_POOL, female));
  }

  @Test
  public void explicitSpeakerOnSpecWins() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    VoiceSpec spec = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE, 27);
    assertEquals(27, manager.kokoroSpeakerId(spec));
  }

  @Test
  public void unknownGenderBareSpecLandsInTheMaleBritishPool() {
    VoiceManager manager = newManager(PlayerVoice.TYPE_A);
    int chosen = manager.kokoroSpeakerId(VoiceSpec.npc(NPCRace.HUMAN, NPCGender.UNKNOWN));
    assertTrue("unknown gender must still resolve British", isBritish(chosen));
    assertTrue(contains(KokoroSpeakerPool.MALE_SPEAKER_POOL, chosen));
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
