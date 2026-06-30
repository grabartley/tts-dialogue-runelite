package com.grahambartley.dialogue;

import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.synthesis.OpenRouterTtsBackend;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

/**
 * Posts the plugin's user-facing chat notices: the once-ever first-run onboarding guide, the
 * once-per-session missing-cloud-key warning, and one-off backend notices surfaced from a backend
 * thread. A fresh instance is created on each start-up, so the per-session guards reset on a
 * stop/start exactly as before; onboarding additionally persists across sessions via {@link
 * #ONBOARDING_SEEN_KEY}.
 */
@Slf4j
public final class ChatNoticeManager {

  /**
   * Hidden persisted flag marking that the first-run onboarding guide has been shown, so it appears
   * exactly once ever rather than every login. Read and set directly through {@link ConfigManager}.
   */
  static final String ONBOARDING_SEEN_KEY = "onboardingSeen";

  /** Chat-markup hex colour for plugin notices, so they stand out red from ordinary game chat. */
  private static final String CHAT_NOTICE_COLOR = "ff3333";

  private static final String ONBOARDING_MESSAGE =
      "Voiced Dialogue is on. The Cloud voice (recommended) needs a free OpenRouter API key: get"
          + " one at openrouter.ai and paste it into the plugin's Cloud Voice settings. While Cloud"
          + " is active your dialogue text is sent to OpenRouter to be voiced. Prefer to stay"
          + " offline? Set Voice Backend to Local for a free, no-key voice (basic and"
          + " neutral-only).";

  private final Client client;
  private final ConfigManager configManager;
  private final ClientThread clientThread;
  private final TTSDialogueConfig config;

  private boolean onboardingChecked;
  private boolean cloudKeyNoticeChecked;

  public ChatNoticeManager(
      Client client,
      ConfigManager configManager,
      ClientThread clientThread,
      TTSDialogueConfig config) {
    this.client = client;
    this.configManager = configManager;
    this.clientThread = clientThread;
    this.config = config;
  }

  /**
   * Surfaces a one-time cloud-backend notice (e.g. "add an OpenRouter API key") to the player.
   * Fired from a backend thread, so the chat write is hopped onto the client thread.
   */
  public void notifyFromBackendThread(String message) {
    log.warn(message);
    clientThread.invokeLater(() -> addGameMessage(message));
  }

  /**
   * Shows the first-run onboarding guide exactly once, gated by the persisted {@link
   * #ONBOARDING_SEEN_KEY} flag and a per-session guard. Must be called on the game thread.
   */
  public void maybeShowOnboarding() {
    if (onboardingChecked) {
      return;
    }
    onboardingChecked = true;
    Boolean seen =
        configManager.getConfiguration(TTSDialogueConfig.GROUP, ONBOARDING_SEEN_KEY, Boolean.class);
    if (!shouldShowOnboarding(seen)) {
      return;
    }
    addGameMessage(ONBOARDING_MESSAGE);
    configManager.setConfiguration(TTSDialogueConfig.GROUP, ONBOARDING_SEEN_KEY, true);
  }

  /**
   * Pure decision for {@link #maybeShowOnboarding}: show the guide unless the persisted seen flag
   * is already true. A {@code null} flag (never set) means a fresh install, so the guide shows.
   */
  static boolean shouldShowOnboarding(Boolean seenFlag) {
    return !Boolean.TRUE.equals(seenFlag);
  }

  /**
   * Posts the missing-cloud-key notice once per session when running Cloud-with-no-key, so a player
   * who selected Cloud but never set a key is told their voice is effectively off. Must be called
   * on the game thread. {@code keyAvailable} is the active backend's availability.
   */
  public void maybeWarnMissingCloudKey(boolean keyAvailable) {
    if (cloudKeyNoticeChecked) {
      return;
    }
    cloudKeyNoticeChecked = true;
    if (shouldWarnMissingCloudKey(config.voiceBackend(), keyAvailable)) {
      addGameMessage(OpenRouterTtsBackend.NO_KEY_NOTICE);
    }
  }

  /**
   * Pure decision for {@link #maybeWarnMissingCloudKey}: warn only when Cloud is the active backend
   * and its key is unavailable. Local needs no key, so it never warns.
   */
  static boolean shouldWarnMissingCloudKey(
      TTSDialogueConfig.VoiceBackend backend, boolean keyAvailable) {
    return backend == TTSDialogueConfig.VoiceBackend.CLOUD && !keyAvailable;
  }

  /**
   * Posts a single red, plugin-tagged notice into the game chat box. Red marks it as a plugin
   * notice that stands out from ordinary dialogue and game spam. Must be called on the client
   * thread.
   */
  private void addGameMessage(String message) {
    String line = "<col=" + CHAT_NOTICE_COLOR + ">[Voiced Dialogue] " + message + "</col>";
    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", line, null);
  }
}
