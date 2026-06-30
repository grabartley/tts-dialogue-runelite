package com.grahambartley.voice;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.synthesis.CharacterProfile;
import org.junit.Test;

/**
 * The shared profile gate: a profile is resolved only when character profiles are enabled,
 * otherwise {@code null} (which keeps a request's cache key byte-for-byte identical to the
 * pre-profile behaviour).
 */
public class ProfileResolverTest {

  private final VoiceManager voiceManager = mock(VoiceManager.class);
  private final TTSDialogueConfig config = mock(TTSDialogueConfig.class);
  private final ProfileResolver resolver = new ProfileResolver(voiceManager, config);

  @Test
  public void returnsNullAndSkipsLookupWhenProfilesDisabled() {
    when(config.cloudCharacterProfiles()).thenReturn(false);

    assertNull(resolver.resolve(VoiceManager.SPEAKER_PLAYER, null));
    verify(voiceManager, never()).resolveProfile(VoiceManager.SPEAKER_PLAYER, null);
  }

  @Test
  public void delegatesToVoiceManagerWhenProfilesEnabled() {
    CharacterProfile profile = mock(CharacterProfile.class);
    when(config.cloudCharacterProfiles()).thenReturn(true);
    when(voiceManager.resolveProfile(VoiceManager.SPEAKER_NPC, "Bob")).thenReturn(profile);

    assertSame(profile, resolver.resolve(VoiceManager.SPEAKER_NPC, "Bob"));
  }
}
