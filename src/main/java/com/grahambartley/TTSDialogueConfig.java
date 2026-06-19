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

  /**
   * Which synthesis backend dialogue routes through. {@code LOCAL} is the offline, in-process
   * Kokoro engine (default); {@code LOCAL_GPU} and {@code CLOUD} are reserved for the emotional
   * backends and fall back to the local engine until those backends ship.
   */
  enum VoiceBackend {
    LOCAL,
    LOCAL_GPU,
    CLOUD
  }

  @ConfigItem(
      keyName = "voiceBackend",
      name = "Voice Backend",
      description =
          "Which synthesis engine to use. Local is the offline, in-process Kokoro voice (default)."
              + " Other options fall back to Local until their backends are installed.",
      position = 0,
      section = synthesisSection)
  default VoiceBackend voiceBackend() {
    return VoiceBackend.LOCAL;
  }

  @ConfigItem(
      keyName = "enableEmotion",
      name = "Enable Emotion",
      description =
          "Carry detected emotion through to synthesis. The local Kokoro backend is neutral-only, so"
              + " emotion is only audible on the emotional backends.",
      position = 1,
      section = synthesisSection)
  default boolean enableEmotion() {
    return true;
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
      keyName = "debugMode",
      name = "Debug Mode",
      description = "Show detailed NPC race/gender resolution info in logs",
      position = 4,
      section = generalSection)
  default boolean debugMode() {
    return false;
  }
}
