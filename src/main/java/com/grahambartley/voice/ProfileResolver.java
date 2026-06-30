package com.grahambartley.voice;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.synthesis.CharacterProfile;

/**
 * Resolves the per-speaker {@link CharacterProfile} when character profiles are enabled, else
 * {@code null}. A null profile keeps the request (and its cache key) byte-for-byte identical to the
 * pre-profile behaviour. Shared by every synthesis path (dialogue, prefetch, public chat).
 */
public final class ProfileResolver {

  private final VoiceManager voiceManager;
  private final TTSDialogueConfig config;

  public ProfileResolver(VoiceManager voiceManager, TTSDialogueConfig config) {
    this.voiceManager = voiceManager;
    this.config = config;
  }

  public CharacterProfile resolve(String speaker, String npcName) {
    return config.cloudCharacterProfiles() ? voiceManager.resolveProfile(speaker, npcName) : null;
  }
}
