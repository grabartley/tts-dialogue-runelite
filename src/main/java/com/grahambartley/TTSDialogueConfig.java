package com.grahambartley;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("ttsDialogue")
public interface TTSDialogueConfig extends Config {

  @ConfigSection(name = "General Settings", description = "General TTS settings", position = 0)
  String generalSection = "general";

  @ConfigSection(
      name = "Voice Settings",
      description = "Configure player and default voices",
      position = 1)
  String voiceSection = "voices";

  @ConfigSection(
      name = "Synthesis",
      description = "Choose the synthesis backend and emotion behaviour",
      position = 2)
  String synthesisSection = "synthesis";

  @ConfigSection(
      name = "Cloud (OpenRouter)",
      description =
          "OpenRouter cloud TTS settings. Used only when Voice Backend is Cloud. Dialogue text leaves"
              + " your machine and is sent to OpenRouter when this backend is active.",
      position = 4)
  String cloudOpenRouterSection = "cloudOpenRouter";

  /**
   * Which synthesis backend dialogue routes through. {@code CLOUD} is the OpenRouter cloud backend
   * (default, cloud-first): it falls back to the local engine and warns once when no API key is
   * set. {@code LOCAL} is the offline, neutral-only Kokoro engine.
   */
  enum VoiceBackend {
    LOCAL,
    CLOUD
  }

  @ConfigItem(
      keyName = "voiceBackend",
      name = "Voice Backend",
      description =
          "Which synthesis engine to use. Cloud is the OpenRouter cloud backend (default): it needs"
              + " an API key and falls back to the local voice with a one-time notice until you add"
              + " one. Local is the offline, neutral-only Kokoro voice.",
      position = 0,
      section = synthesisSection)
  default VoiceBackend voiceBackend() {
    return VoiceBackend.CLOUD;
  }

  @ConfigItem(
      keyName = "enableEmotion",
      name = "Enable Emotion",
      description =
          "Carry the emotion detected from the speaker's chat-head animation through to synthesis."
              + " Per-model emotion rendering is still being rolled out, so today every line is"
              + " voiced as Neutral on both backends regardless of this setting.",
      position = 1,
      section = synthesisSection)
  default boolean enableEmotion() {
    return true;
  }

  @ConfigItem(
      keyName = "openRouterApiKey",
      name = "OpenRouter API Key",
      description =
          "Your OpenRouter API key. Required for the Cloud voice backend. Stored locally and never"
              + " bundled with the plugin.",
      position = 0,
      secret = true,
      section = cloudOpenRouterSection)
  default String openRouterApiKey() {
    return "";
  }

  @ConfigItem(
      keyName = "volume",
      name = "Dialogue Volume",
      description = "Volume of the spoken dialogue (0–100)",
      position = 0,
      section = generalSection)
  @Range(min = 0, max = 100)
  default int volume() {
    return 100;
  }

  @ConfigItem(
      keyName = "enableRaceBasedVoices",
      name = "Enable Automatic NPC Voices",
      description =
          "Automatically pick a Kokoro voice per NPC from the bundled race and gender voice table."
              + " When off, every NPC uses the default voice.",
      position = 1,
      section = generalSection)
  default boolean enableRaceBasedVoices() {
    return true;
  }

  @ConfigItem(
      keyName = "playerVoice",
      name = "Player Voice",
      description = "Voice used for player dialogue",
      position = 0,
      section = voiceSection)
  default VoiceManager.VoiceProfile playerVoice() {
    return VoiceManager.VoiceProfile.PLAYER_MALE;
  }

  @ConfigItem(
      keyName = "enableFallbacks",
      name = "Enable Voice Fallbacks",
      description =
          "When an NPC's race isn't in the voice table, fall back to a gender-appropriate human"
              + " voice. When off, those NPCs use the single default voice.",
      position = 3,
      section = generalSection)
  default boolean enableFallbacks() {
    return true;
  }

  @ConfigItem(
      keyName = "persistentCache",
      name = "Persistent Audio Cache",
      description =
          "Save synthesized dialogue to disk so repeated lines play instantly across sessions and"
              + " cloud backends are not re-billed for audio you have already heard. Cache lives in"
              + " ~/.runelite/tts-dialogue/cache and is size-bounded.",
      position = 5,
      section = generalSection)
  default boolean persistentCache() {
    return true;
  }

  @ConfigItem(
      keyName = "debugMode",
      name = "Debug Mode",
      description = "Show detailed NPC race/gender resolution info in logs",
      position = 4,
      section = generalSection)
  default boolean debugMode() {
    return false;
  }
}
