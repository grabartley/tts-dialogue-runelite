package com.grahambartley.synthesis;

import com.grahambartley.VoiceManager;
import com.grahambartley.tts.KokoroTtsEngine;
import com.grahambartley.tts.Pcm;
import java.util.EnumSet;

/**
 * The in-process Kokoro engine exposed as a {@link SynthesisBackend}.
 *
 * <p>This is the default backend and the universal fallback. It maps a {@link VoiceSpec} to a
 * Kokoro speaker id via {@link VoiceManager#kokoroSpeakerId(VoiceSpec)} and hands the text to the
 * bundled {@link KokoroTtsEngine}. Kokoro is deliberately neutral-only ({@link Emotion#NEUTRAL});
 * emotional delivery is reserved for the GPU and cloud backends, so this backend advertises only
 * neutral and {@link BackendProvider} downgrades anything else before it reaches here.
 */
public final class LocalKokoroBackend implements SynthesisBackend {

  private final KokoroTtsEngine engine;
  private final VoiceManager voiceManager;

  public LocalKokoroBackend(KokoroTtsEngine engine, VoiceManager voiceManager) {
    this.engine = engine;
    this.voiceManager = voiceManager;
  }

  @Override
  public String id() {
    return BackendProvider.LOCAL_KOKORO_ID;
  }

  @Override
  public boolean isAvailable() {
    // The bundled engine is always present; it only becomes unusable if model load failed.
    return !engine.isFailed();
  }

  @Override
  public EnumSet<Emotion> supportedEmotions() {
    return EnumSet.of(Emotion.NEUTRAL);
  }

  @Override
  public Pcm synthesize(SynthesisRequest request) {
    int speakerId = voiceManager.kokoroSpeakerId(request.voice());
    return engine.synthesize(request.text(), speakerId);
  }

  @Override
  public void warmUp() {
    engine.load();
  }

  @Override
  public void close() {
    engine.close();
  }
}
