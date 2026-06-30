package com.grahambartley.synthesis;

import com.grahambartley.tts.Pcm;
import java.util.EnumSet;

/**
 * The {@link TtsModelStrategy} for Gemini 3.1 Flash TTS, the one OpenRouter speech model with both
 * a voice catalog rich enough to map every race/gender and full emotion support. Voices come from
 * {@link GeminiVoiceMap}, emotion is rendered as an inline {@link GeminiEmotionStyle} tag, and the
 * {@code pcm} response is a raw headerless stream of signed 16-bit little-endian mono samples at 24
 * kHz, decoded by {@link RawPcmDecoder} at its true rate so playback is not pitch-shifted.
 */
final class GeminiTtsModel implements TtsModelStrategy {

  static final String MODEL_ID = "google/gemini-3.1-flash-tts-preview";

  static final String RESPONSE_FORMAT = "pcm";

  /** OpenRouter {@code pcm} output is headerless 16-bit LE mono at this rate. */
  static final int SAMPLE_RATE = 24_000;

  private final GeminiVoiceMap voiceMap = new GeminiVoiceMap();

  @Override
  public String modelId() {
    return MODEL_ID;
  }

  @Override
  public String responseFormat() {
    return RESPONSE_FORMAT;
  }

  @Override
  public EnumSet<Emotion> supportedEmotions() {
    return EnumSet.copyOf(GeminiEmotionStyle.SUPPORTED);
  }

  @Override
  public String voiceFor(VoiceSpec voice) {
    return voiceMap.voiceFor(voice);
  }

  @Override
  public String styleInput(String text, Emotion emotion) {
    return GeminiEmotionStyle.apply(text, emotion);
  }

  @Override
  public Pcm decodeResponse(byte[] bytes) {
    return RawPcmDecoder.decode(bytes, SAMPLE_RATE);
  }
}
