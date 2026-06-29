package com.grahambartley.synthesis;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import org.junit.Test;

/**
 * The backward-compatible constructors and the {@code skipTranslation} flag added for issue #138:
 * existing call sites must keep producing a translating request, and {@code withEmotion} must carry
 * the flag through so a re-emotioned copy is not silently re-translated.
 */
public class SynthesisRequestTest {

  private static final VoiceSpec VOICE = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE);

  @Test
  public void legacyConstructorsDefaultToTranslating() {
    assertFalse(
        "the 3-arg form leaves translation enabled",
        new SynthesisRequest("hi", VOICE, Emotion.NEUTRAL).skipTranslation());
    assertFalse(
        "the 4-arg form leaves translation enabled",
        new SynthesisRequest("hi", VOICE, Emotion.NEUTRAL, null).skipTranslation());
  }

  @Test
  public void withEmotionPreservesSkipTranslation() {
    SynthesisRequest publicChat = new SynthesisRequest("hi", VOICE, Emotion.NEUTRAL, null, true);
    assertTrue(
        "a re-emotioned copy keeps skip-translation, so a downgrade never re-enables translation",
        publicChat.withEmotion(Emotion.HAPPY).skipTranslation());

    SynthesisRequest dialogue = new SynthesisRequest("hi", VOICE, Emotion.HAPPY, null, false);
    assertFalse(
        "a normal line stays translating after a downgrade",
        dialogue.withEmotion(Emotion.NEUTRAL).skipTranslation());
  }

  @Test
  public void legacyConstructorsDefaultToNpcSpeakerClass() {
    assertFalse(
        "the 3-arg form voices as an NPC line",
        new SynthesisRequest("hi", VOICE, Emotion.NEUTRAL).player());
    assertFalse(
        "the 4-arg form voices as an NPC line",
        new SynthesisRequest("hi", VOICE, Emotion.NEUTRAL, null).player());
    assertFalse(
        "the 5-arg form voices as an NPC line",
        new SynthesisRequest("hi", VOICE, Emotion.NEUTRAL, null, true).player());
  }

  @Test
  public void withEmotionPreservesPlayerClass() {
    SynthesisRequest playerLine =
        new SynthesisRequest("hi", VOICE, Emotion.NEUTRAL, null, false, true);
    assertTrue(
        "a re-emotioned player line stays a player line, so it keeps the player Speaking Style",
        playerLine.withEmotion(Emotion.HAPPY).player());

    SynthesisRequest npcLine = new SynthesisRequest("hi", VOICE, Emotion.HAPPY, null, false, false);
    assertFalse(
        "a re-emotioned NPC line stays an NPC line", npcLine.withEmotion(Emotion.NEUTRAL).player());
  }
}
