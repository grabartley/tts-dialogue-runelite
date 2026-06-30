package com.grahambartley.dialogue;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.synthesis.BackendProvider;
import com.grahambartley.synthesis.CharacterProfile;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import com.grahambartley.voice.ProfileResolver;
import com.grahambartley.voice.VoiceManager;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.widgets.Widget;

/**
 * Warms the cache for the dialogue options the player can currently see. Each option's text is the
 * line the player will speak if it is picked, so it is built into the exact same {@link
 * SynthesisRequest} (player voice, player profile, neutral) the dispatcher would produce for that
 * line and handed to the off-thread prefetcher. The "Select an Option" header and blank rows are
 * skipped. Only touches the client on the game thread; never throws.
 */
public final class DialoguePrefetchCoordinator {

  private static final String OPTION_HEADER = "Select an Option";

  private final VoiceManager voiceManager;
  private final ProfileResolver profileResolver;
  private final DialogueTextCleaner textCleaner;
  private final DialoguePrefetcher prefetcher;
  private final BackendProvider backendProvider;
  private final TTSDialogueConfig config;

  public DialoguePrefetchCoordinator(
      VoiceManager voiceManager,
      ProfileResolver profileResolver,
      DialogueTextCleaner textCleaner,
      DialoguePrefetcher prefetcher,
      BackendProvider backendProvider,
      TTSDialogueConfig config) {
    this.voiceManager = voiceManager;
    this.profileResolver = profileResolver;
    this.textCleaner = textCleaner;
    this.prefetcher = prefetcher;
    this.backendProvider = backendProvider;
    this.config = config;
  }

  void prefetchOptions(Widget options) {
    if (!config.prefetch() || !backendProvider.active().isAvailable()) {
      return;
    }
    Widget[] children = options.getDynamicChildren();
    if (children == null || children.length == 0) {
      return;
    }
    VoiceSpec voice = voiceManager.resolveVoice(VoiceManager.SPEAKER_PLAYER, null);
    CharacterProfile profile = profileResolver.resolve(VoiceManager.SPEAKER_PLAYER, null);
    List<SynthesisRequest> candidates = new ArrayList<>(children.length);
    for (Widget child : children) {
      if (child == null) {
        continue;
      }
      String raw = child.getText();
      if (raw == null) {
        continue;
      }
      String cleaned = textCleaner.clean(raw);
      if (cleaned.isEmpty() || OPTION_HEADER.equalsIgnoreCase(cleaned)) {
        continue;
      }
      candidates.add(
          new SynthesisRequest(
              cleaned,
              voice,
              Emotion.NEUTRAL,
              profile,
              /* skipTranslation= */ false,
              /* player= */ true));
    }
    prefetcher.offer(candidates);
  }
}
