package com.grahambartley.synthesis;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.tts.CaveEchoPolicy;
import com.grahambartley.tts.DialogueAudioService;
import com.grahambartley.voice.EmotionResolver;
import com.grahambartley.voice.ProfileResolver;
import com.grahambartley.voice.VoiceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds {@link SynthesisRequest}s for dialogue and public-chat lines and hands them to the
 * off-thread synth + playback pipeline. Every speak path shares the same availability guard, voice
 * resolution, profile resolution, emotion resolution, and cave-echo gate, so this is the single
 * place a line becomes a request. Never blocks the game thread.
 */
@Slf4j
public final class SynthesisDispatcher {

  private final VoiceManager voiceManager;
  private final EmotionResolver emotionResolver;
  private final ProfileResolver profileResolver;
  private final CaveEchoPolicy caveEchoPolicy;
  private final TTSDialogueConfig config;
  private final BackendProvider backendProvider;
  private final DialogueAudioService audioService;

  public SynthesisDispatcher(
      VoiceManager voiceManager,
      EmotionResolver emotionResolver,
      ProfileResolver profileResolver,
      CaveEchoPolicy caveEchoPolicy,
      TTSDialogueConfig config,
      BackendProvider backendProvider,
      DialogueAudioService audioService) {
    this.voiceManager = voiceManager;
    this.emotionResolver = emotionResolver;
    this.profileResolver = profileResolver;
    this.caveEchoPolicy = caveEchoPolicy;
    this.config = config;
    this.backendProvider = backendProvider;
    this.audioService = audioService;
  }

  /**
   * Speaks a dialogue line. The caller passes the speaker's chat-head expression animation id (or
   * {@link DialogueWidgetReader#NO_EXPRESSION} when there is no head); it is resolved to an {@link
   * Emotion} and ridden into the request.
   */
  public void speakDialogue(String text, String speaker, String npcName, int headAnimationId) {
    Emotion emotion = emotionResolver.resolve(headAnimationId, config.cloudEmotion());
    if (config.debugMode()) {
      log.info("[TTS voice] resolved emotion {} for head animation {}", emotion, headAnimationId);
    }
    VoiceSpec voice = voiceManager.resolveVoice(speaker, npcName);
    boolean player = VoiceManager.SPEAKER_PLAYER.equals(speaker);
    dispatch(
        new SynthesisRequest(
            text,
            voice,
            emotion,
            profileResolver.resolve(speaker, npcName),
            /* skipTranslation= */ false,
            player));
  }

  /**
   * Voices the player's own public chat through the same player voice path as their dialogue lines,
   * but always neutral (public chat has no chat-head) and with translation/global-quirk bypassed,
   * so chat is spoken exactly as typed.
   */
  public void speakPublicChat(String text) {
    VoiceSpec voice = voiceManager.resolveVoice(VoiceManager.SPEAKER_PLAYER, null);
    dispatch(
        new SynthesisRequest(
            text,
            voice,
            Emotion.NEUTRAL,
            profileResolver.resolve(VoiceManager.SPEAKER_PLAYER, null),
            /* skipTranslation= */ true,
            /* player= */ true));
  }

  /**
   * Hands a built request to the off-thread synth pipeline, guarded by the availability check every
   * speak path needs: no-op when the active backend is unavailable.
   */
  private void dispatch(SynthesisRequest request) {
    if (!backendProvider.active().isAvailable()) {
      return;
    }
    audioService.speak(request, caveEchoPolicy.shouldEcho());
  }
}
