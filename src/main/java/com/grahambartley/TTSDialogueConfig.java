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
          "Automatically pick a Kokoro voice per NPC based on race and gender detection. When off,"
              + " every NPC uses the default voice.",
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
          "When an NPC's race can't be detected, fall back to a gender-appropriate human voice."
              + " When off, undetected NPCs use the single default voice.",
      position = 3,
      section = generalSection)
  default boolean enableFallbacks() {
    return true;
  }

  @ConfigItem(
      keyName = "debugMode",
      name = "Debug Mode",
      description = "Show detailed NPC race/gender detection info in logs",
      position = 4,
      section = generalSection)
  default boolean debugMode() {
    return false;
  }
}
