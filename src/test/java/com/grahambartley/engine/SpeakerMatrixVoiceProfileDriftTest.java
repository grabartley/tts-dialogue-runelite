package com.grahambartley.engine;

import static org.junit.Assert.assertEquals;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.VoiceManager.VoiceProfile;
import org.junit.Test;

/**
 * Drift guard between the standalone engine's {@link SpeakerMatrix} and the plugin's {@link
 * VoiceProfile} table.
 *
 * <p>The engine ships as its own self-contained runtime and cannot depend on the RuneLite-coupled
 * plugin classes, so it duplicates the {@code (player, race, gender) -> speakerId} matrix. If the
 * two copies ever diverge, the same NPC would get a different voice depending on whether synthesis
 * runs in-process (plugin) or through the external engine. This test loads BOTH classes directly
 * (the engine subproject is on the plugin test classpath via {@code testImplementation
 * project(':engine')}, and {@code VoiceProfile} resolves through {@code testImplementation
 * net.runelite:client}) and asserts every mapping is identical, so the duplication can never
 * silently drift.
 *
 * <p>Lives in package {@code com.grahambartley.engine} so it can call the package-private {@code
 * SpeakerMatrix.speakerId} without widening its visibility.
 */
public class SpeakerMatrixVoiceProfileDriftTest {

  @Test
  public void everyVoiceProfileMatchesSpeakerMatrix() {
    for (VoiceProfile profile : VoiceProfile.values()) {
      boolean player = profile == VoiceProfile.PLAYER_MALE || profile == VoiceProfile.PLAYER_FEMALE;
      String race = profile.getRace().name();
      String gender = profile.getGender().name();

      int fromEngine = SpeakerMatrix.speakerId(player, race, gender);
      assertEquals(
          "speakerId drift for "
              + profile.name()
              + " (player="
              + player
              + ", race="
              + race
              + ", gender="
              + gender
              + ")",
          profile.getSpeakerId(),
          fromEngine);
    }
  }

  /**
   * The two copies must also agree on fallbacks: an unrecognised race resolves to the human voice
   * and a non-FEMALE gender is treated as male. The plugin's {@link VoiceProfile} table has no
   * "unknown race" entry, so this pins the engine's fallback against the plugin's known human
   * voices directly.
   */
  @Test
  public void fallbacksMatchHumanVoices() {
    assertEquals(
        VoiceProfile.HUMAN_MALE.getSpeakerId(),
        SpeakerMatrix.speakerId(false, NPCRace.UNKNOWN.name(), NPCGender.MALE.name()));
    assertEquals(
        VoiceProfile.HUMAN_FEMALE.getSpeakerId(),
        SpeakerMatrix.speakerId(false, NPCRace.UNKNOWN.name(), NPCGender.FEMALE.name()));
    // Unknown gender collapses to male in both copies.
    assertEquals(
        VoiceProfile.HUMAN_MALE.getSpeakerId(),
        SpeakerMatrix.speakerId(false, NPCRace.HUMAN.name(), NPCGender.UNKNOWN.name()));
  }
}
