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

  /**
   * Geographic bias for OpenRouter provider routing, offered as a fixed dropdown. {@link #AUTO}
   * sends no preference (the default, OpenRouter picks the provider); every other value is injected
   * into the request's provider preferences as its {@link #code()}.
   */
  enum CloudRegion {
    AUTO("", "Auto (no preference)"),
    US("us", "United States"),
    EU("eu", "European Union"),
    UK("uk", "United Kingdom"),
    CANADA("ca", "Canada"),
    ASIA_PACIFIC("apac", "Asia Pacific");

    private final String code;
    private final String label;

    CloudRegion(String code, String label) {
      this.code = code;
      this.label = label;
    }

    /** The region token sent to OpenRouter, or blank for {@link #AUTO} (no preference). */
    public String code() {
      return code;
    }

    @Override
    public String toString() {
      return label;
    }
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
              + " The Cloud voice renders happy, sad, angry, and scared delivery; the Local voice is"
              + " neutral-only, so its lines stay neutral. Turn this off to voice every line as"
              + " Neutral.",
      position = 1,
      section = synthesisSection)
  default boolean enableEmotion() {
    return true;
  }

  @ConfigItem(
      keyName = "enableCharacterProfiles",
      name = "Enable Character Profiles",
      description =
          "Steer each speaker's delivery with a per-character voice profile (accent, style, pace),"
              + " resolved from the bundled profile table and rendered as a Cloud direction block in"
              + " front of the line. Emotion still layers on top. Cloud only (the Local voice ignores"
              + " it). Adds a little to each Cloud request; turn off for the cheapest, plainest"
              + " delivery.",
      position = 2,
      section = synthesisSection)
  default boolean enableCharacterProfiles() {
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
      keyName = "maxCloudCharsPerLine",
      name = "Max Cloud Characters",
      description =
          "Hard cap on how many characters of a single dialogue line are sent to the cloud backend."
              + " Cloud TTS is billed per character, so an unusually long line is truncated at a"
              + " sentence or word boundary before sending. OSRS lines are short, so this only bites"
              + " pathological cases. Set to 0 to disable the cap.",
      position = 1,
      section = cloudOpenRouterSection)
  @Range(min = 0, max = 5000)
  default int maxCloudCharsPerLine() {
    return 600;
  }

  @ConfigItem(
      keyName = "cloudSpeedPercent",
      name = "Cloud Speaking Pace",
      description =
          "Speaking pace for the cloud backend, as a percentage of normal (100 = normal). Sent as"
              + " the OpenRouter speed parameter only when not 100; the active model may ignore it."
              + " Has no effect on the local backend.",
      position = 2,
      section = cloudOpenRouterSection)
  @Range(min = 50, max = 200)
  default int cloudSpeedPercent() {
    return 100;
  }

  @ConfigItem(
      keyName = "providerRegion",
      name = "Provider Region",
      description =
          "Optional geographic bias for OpenRouter provider routing, injected into the request when"
              + " set. Leave on Auto (default) to let OpenRouter pick the best provider"
              + " automatically. Used only by the Cloud backend.",
      position = 3,
      section = cloudOpenRouterSection)
  default CloudRegion providerRegion() {
    return CloudRegion.AUTO;
  }

  @ConfigItem(
      keyName = "targetLanguage",
      name = "Spoken Language",
      description =
          "Language dialogue is spoken in. English (default) speaks the original line directly. Any"
              + " other language routes each line through a translation model first, preserving"
              + " names, places, and item terms, then voices the translation. Adds a translation"
              + " request per new line. Used only by the Cloud backend.",
      position = 4,
      section = cloudOpenRouterSection)
  default String targetLanguage() {
    return "English";
  }

  @ConfigItem(
      keyName = "enablePrefetch",
      name = "Prefetch Dialogue Audio",
      description =
          "Warm the audio cache for the dialogue options you can see, so the line you pick next plays"
              + " instantly. Raises Cloud API spend on branches you never choose. Used only when the"
              + " Cloud backend is active; the local voice is free, so this just speeds it up.",
      position = 5,
      section = cloudOpenRouterSection)
  default boolean enablePrefetch() {
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
      keyName = "playerProfileAccent",
      name = "Player Accent",
      description =
          "Accent for your character's Cloud voice profile. British by default; this is a British"
              + " medieval fantasy world. Used only when Character Profiles are on and the Cloud"
              + " backend is active.",
      position = 1,
      section = voiceSection)
  default String playerProfileAccent() {
    return "British English, Received Pronunciation, as heard in southern England.";
  }

  @ConfigItem(
      keyName = "playerProfileStyle",
      name = "Player Style",
      description =
          "Persona and delivery style for your character's Cloud voice profile. Describe who your"
              + " adventurer is. Used only when Character Profiles are on and the Cloud backend is"
              + " active.",
      position = 2,
      section = voiceSection)
  default String playerProfileStyle() {
    return "A seasoned medieval fantasy adventurer: brave, resolute, and well-spoken, carrying the"
        + " quiet confidence of someone who has seen the whole of Gielinor.";
  }

  @ConfigItem(
      keyName = "playerProfilePace",
      name = "Player Pace",
      description =
          "Speaking pace for your character's Cloud voice profile. Used only when Character Profiles"
              + " are on and the Cloud backend is active.",
      position = 3,
      section = voiceSection)
  default String playerProfilePace() {
    return "Even and assured, unhurried but purposeful.";
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
      keyName = "diskCacheMaxMiB",
      name = "Cache Size Limit (MiB)",
      description =
          "Maximum size of the on-disk audio cache in MiB. When a new clip would push the cache over"
              + " this limit, the oldest clips are deleted first (FIFO) to make room, so the cache"
              + " never grows past it. Set to 0 for no limit (the cache grows with what you hear and"
              + " is never evicted). Only applies when Persistent Audio Cache is on.",
      position = 6,
      section = generalSection)
  @Range(min = 0, max = 4096)
  default int diskCacheMaxMiB() {
    return 256;
  }

  @ConfigItem(
      keyName = "wikiLookupFallback",
      name = "Auto-learn New NPCs",
      description =
          "When an NPC isn't in the bundled voice table (e.g. one added to the game since the last"
              + " plugin update), look its race, gender and ethnicity up on the Old School RuneScape"
              + " Wiki once, then cache the result locally so it voices correctly from then on. The"
              + " first line for such an NPC still uses the default voice while the lookup runs. Off"
              + " by default; when on it makes a network request (the NPC's name) to the wiki.",
      position = 7,
      section = generalSection)
  default boolean wikiLookupFallback() {
    return false;
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
