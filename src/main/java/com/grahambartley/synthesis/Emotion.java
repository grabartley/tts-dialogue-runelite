package com.grahambartley.synthesis;

/**
 * The emotional colour carried end-to-end through synthesis.
 *
 * <p>Detection (#26) reads the speaker's chat-head animation and threads one of these into every
 * {@link SynthesisRequest}; each {@link SynthesisBackend} renders it however its engine allows.
 * Backends advertise the subset they can voice via {@link SynthesisBackend#supportedEmotions()},
 * and {@link BackendProvider} downgrades anything unsupported to {@link #NEUTRAL} before synthesis
 * so backends never special-case emotions they cannot produce.
 */
public enum Emotion {
  NEUTRAL,
  HAPPY,
  SAD,
  ANGRY,
  SCARED
}
