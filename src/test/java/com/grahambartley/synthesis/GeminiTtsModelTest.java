package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;

import com.grahambartley.tts.Pcm;
import com.grahambartley.voice.VoiceManager.NPCGender;
import com.grahambartley.voice.VoiceManager.NPCRace;
import java.util.EnumSet;
import org.junit.Test;

/** The Gemini model strategy: id, format, emotion set, and delegation to voice/style/decode. */
public class GeminiTtsModelTest {

  private final GeminiTtsModel model = new GeminiTtsModel();

  @Test
  public void identifiesTheGeminiModelAndPcmFormat() {
    assertEquals("google/gemini-3.1-flash-tts-preview", model.modelId());
    assertEquals("pcm", model.responseFormat());
  }

  @Test
  public void advertisesTheFullGeminiEmotionSet() {
    assertEquals(
        EnumSet.of(Emotion.NEUTRAL, Emotion.HAPPY, Emotion.SAD, Emotion.ANGRY, Emotion.SCARED),
        model.supportedEmotions());
  }

  @Test
  public void voiceComesFromTheGeminiVoiceMap() {
    VoiceSpec spec = VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE);
    assertEquals(new GeminiVoiceMap().voiceFor(spec), model.voiceFor(spec));
  }

  @Test
  public void emotionIsRenderedAsAnInlineStyleTag() {
    assertEquals("[happy] Hello", model.styleInput("Hello", Emotion.HAPPY));
    assertEquals("Hello", model.styleInput("Hello", Emotion.NEUTRAL));
  }

  @Test
  public void decodesRawPcmAt24k() {
    Pcm pcm = model.decodeResponse(RawPcmDecoderTest.raw(new short[] {0, 16384, -16384}));
    assertEquals(24_000, pcm.getSampleRate());
    assertEquals(3, pcm.getSamples().length);
  }
}
