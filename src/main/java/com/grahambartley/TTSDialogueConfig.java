package com.grahambartley;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("ttsDialogue")
public interface TTSDialogueConfig extends Config {

  @ConfigSection(
      name = "General",
      description = "Backend choice and settings shared by both voice backends",
      position = 0)
  String generalSection = "general";

  @ConfigSection(
      name = "Cloud Voice (OpenRouter)",
      description =
          "Settings for the recommended Cloud backend, powered by OpenRouter. These apply only when"
              + " Voice Backend is Cloud. While Cloud is active, your dialogue text is sent to"
              + " OpenRouter to be voiced, so it leaves your machine.",
      position = 1)
  String cloudSection = "cloud";

  @ConfigSection(
      name = "Advanced",
      description = "Niche tuning and diagnostics most players never need to touch",
      position = 2,
      closedByDefault = true)
  String advancedSection = "advanced";

  /**
   * Which synthesis backend dialogue routes through. {@code CLOUD} is the OpenRouter cloud backend
   * (default, cloud-first): it warns once and leaves lines unvoiced when no API key is set. {@code
   * LOCAL} is the offline, neutral-only Kokoro engine. The selected backend is the only one used;
   * the two never fall back to each other.
   */
  enum VoiceBackend {
    LOCAL,
    CLOUD
  }

  /**
   * An optional global delivery quirk layered onto every spoken line. {@link #NONE} (the default)
   * changes nothing; any other value appends its {@link #phrase()} to the configured spoken
   * language, so the line is routed through the translation model and rewritten in that register
   * (for example "English" plus Gen Z slang behaves like a "English Gen Z slang" target). Every
   * value is a register or tone, not a dialect, so it stays language-agnostic and composes with any
   * spoken language ("French pirate speak", "Japanese Gen Z slang"). Cloud only.
   */
  enum SpeakingStyle {
    NONE("None", ""),
    GEN_Z("Gen Z Slang", "Gen Z slang"),
    MILLENNIAL("Millennial Slang", "millennial slang"),
    STREET("Street Slang", "casual street slang"),
    FORMAL("Formal & Posh", "very formal and posh"),
    DRAMATIC("Over-Dramatic", "wildly over-dramatic and theatrical"),
    CUTESY("Cutesy & Bubbly", "cutesy, bubbly and over-enthusiastic"),
    PIRATE("Pirate Speak", "pirate speak");

    private final String label;
    private final String phrase;

    SpeakingStyle(String label, String phrase) {
      this.label = label;
      this.phrase = phrase;
    }

    /** Whether this is the no-op default. */
    public boolean isNone() {
      return this == NONE;
    }

    /** The style descriptor appended to the spoken language for the translation model. */
    public String phrase() {
      return phrase;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  // ---------------------------------------------------------------------------
  // General
  // ---------------------------------------------------------------------------

  @ConfigItem(
      keyName = "voiceBackend",
      name = "Voice Backend",
      description =
          "Which engine voices dialogue. Cloud (recommended) uses OpenRouter for expressive,"
              + " per-character voices: it needs a free OpenRouter API key, and while it is active"
              + " your dialogue text is sent to OpenRouter to be voiced. Local is a free, offline,"
              + " no-key voice that runs on your machine but is basic and neutral-only. Only the"
              + " selected backend is used; the two never fall back to each other.",
      position = 0,
      section = generalSection)
  default VoiceBackend voiceBackend() {
    return VoiceBackend.CLOUD;
  }

  @ConfigItem(
      keyName = "playerVoice",
      name = "Player Voice",
      description = "The voice used for your own character's dialogue and public chat.",
      position = 1,
      section = generalSection)
  default VoiceManager.PlayerVoice playerVoice() {
    return VoiceManager.PlayerVoice.TYPE_A;
  }

  @ConfigItem(
      keyName = "volume",
      name = "Dialogue Volume",
      description = "Loudness of the spoken dialogue, from 0 (muted) to 100.",
      position = 2,
      section = generalSection)
  @Range(min = 0, max = 100)
  default int volume() {
    return 20;
  }

  @ConfigItem(
      keyName = "voicePublicChat",
      name = "Voice My Public Chat",
      description =
          "Speak your own public chat messages aloud using your player voice. Voiced exactly as"
              + " typed: spoken language and speaking style are never applied to public chat.",
      position = 3,
      section = generalSection)
  default boolean voicePublicChat() {
    return false;
  }

  @ConfigItem(
      keyName = "prefetch",
      name = "Prefetch Dialogue",
      description =
          "Warm the audio cache for the dialogue options you can see, so the line you pick next"
              + " plays instantly. Works on both backends. On Cloud it can raise spend on branches"
              + " you never choose; the Local voice is free, so it only speeds things up.",
      position = 4,
      section = generalSection)
  default boolean prefetch() {
    return true;
  }

  @ConfigItem(
      keyName = "persistentCache",
      name = "Save Audio To Disk",
      description =
          "Save synthesized dialogue to disk so repeated lines play instantly across sessions and"
              + " the Cloud backend is not re-billed for audio you have already heard. The cache"
              + " lives in ~/.runelite/tts-dialogue/cache and is size-bounded.",
      position = 5,
      section = generalSection)
  default boolean persistentCache() {
    return true;
  }

  // ---------------------------------------------------------------------------
  // Cloud Voice (OpenRouter)
  // ---------------------------------------------------------------------------

  @ConfigItem(
      keyName = "openRouterApiKey",
      name = "OpenRouter API Key",
      description =
          "Your OpenRouter API key, required for the Cloud voice. Create a free key at"
              + " openrouter.ai and paste it here. Stored locally and never bundled with the"
              + " plugin. Without a key, Cloud lines stay silent with a one-time notice.",
      position = 0,
      secret = true,
      section = cloudSection)
  default String openRouterApiKey() {
    return "";
  }

  @ConfigItem(
      keyName = "playerAccent",
      name = "Your Accent",
      description =
          "Accent for your character's Cloud voice. British by default; this is a British medieval"
              + " fantasy world. Used only when Character Voices are on and the Cloud backend is"
              + " active.",
      position = 1,
      section = cloudSection)
  default String playerAccent() {
    return "British English, as spoken in Cambridge, England.";
  }

  @ConfigItem(
      keyName = "playerPersona",
      name = "Your Persona",
      description =
          "Persona and delivery style for your character's Cloud voice. Describe who your adventurer"
              + " is. Used only when Character Voices are on and the Cloud backend is active.",
      position = 2,
      section = cloudSection)
  default String playerPersona() {
    return "Friendly, plucky, warm, and enthusiastic.";
  }

  @ConfigItem(
      keyName = "playerPace",
      name = "Your Pace",
      description =
          "Speaking pace for your character's Cloud voice. Used only when Character Voices are on"
              + " and the Cloud backend is active.",
      position = 3,
      section = cloudSection)
  default String playerPace() {
    return "Normal.";
  }

  @ConfigItem(
      keyName = "cloudCharacterProfiles",
      name = "Character Voices",
      description =
          "Give each speaker a distinct voice (accent, style, pace) drawn from the bundled character"
              + " table, instead of one shared voice for everyone. Adds a little to each Cloud"
              + " request; turn off for the cheapest, plainest delivery.",
      position = 4,
      section = cloudSection)
  default boolean cloudCharacterProfiles() {
    return true;
  }

  @ConfigItem(
      keyName = "cloudEmotion",
      name = "Emotional Delivery",
      description =
          "Carry the emotion read from the speaker's chat-head animation through to the Cloud voice,"
              + " so lines are delivered happy, sad, angry, or scared. Turn this off to voice every"
              + " line neutrally.",
      position = 5,
      section = cloudSection)
  default boolean cloudEmotion() {
    return true;
  }

  @ConfigItem(
      keyName = "cloudLanguage",
      name = "Spoken Language",
      description =
          "Language dialogue is spoken in. Type any language name (e.g. French, Brazilian"
              + " Portuguese, Japanese). English (default) speaks the original line directly. Any"
              + " other language routes each line through a translation model first, preserving"
              + " names, places, and item terms, then voices the translation. Adds a translation"
              + " request per new line.",
      position = 6,
      section = cloudSection)
  default String cloudLanguage() {
    return "English";
  }

  @ConfigItem(
      keyName = "cloudSpeakingStyle",
      name = "Speaking Style",
      description =
          "Optional delivery register layered onto every line, on top of the Spoken Language. None"
              + " (default) changes nothing; any other value rewrites each line in that style (Gen Z"
              + " slang, pirate speak, and so on) via the translation model, so it routes through"
              + " that hop even for English. Leave this on None with English to skip the translation"
              + " model entirely.",
      position = 7,
      section = cloudSection)
  default SpeakingStyle cloudSpeakingStyle() {
    return SpeakingStyle.NONE;
  }

  @ConfigItem(
      keyName = "cloudPace",
      name = "Speaking Pace",
      description =
          "Speaking pace for the Cloud voice, as a percentage of normal (100 = normal). Sent to"
              + " OpenRouter only when not 100; the active model may ignore it.",
      position = 8,
      section = cloudSection)
  @Range(min = 50, max = 200)
  default int cloudPace() {
    return 100;
  }

  @ConfigItem(
      keyName = "autoLearnNewNpcs",
      name = "Auto-learn New NPCs",
      description =
          "When an NPC isn't in the bundled voice table (e.g. one added to the game since the last"
              + " plugin update), look its race, gender and ethnicity up on the Old School RuneScape"
              + " Wiki once, then cache the result locally so it voices correctly from then on. The"
              + " first line for such an NPC still uses the default voice while the lookup runs. Off"
              + " by default; when on it makes a network request (the NPC's name) to the wiki.",
      position = 9,
      section = cloudSection)
  default boolean autoLearnNewNpcs() {
    return false;
  }

  // ---------------------------------------------------------------------------
  // Advanced
  // ---------------------------------------------------------------------------

  @ConfigItem(
      keyName = "cacheSizeLimitMiB",
      name = "Cache Size Limit (MiB)",
      description =
          "Maximum size of the on-disk audio cache in MiB. When a new clip would push the cache over"
              + " this limit, the oldest clips are deleted first (FIFO) to make room, so the cache"
              + " never grows past it. Set to 0 for no limit. Only applies when Save Audio To Disk"
              + " is on.",
      position = 0,
      section = advancedSection)
  @Range(min = 0, max = 4096)
  default int cacheSizeLimitMiB() {
    return 1024;
  }

  @ConfigItem(
      keyName = "cloudMaxChars",
      name = "Max Characters Per Line",
      description =
          "Hard cap on how many characters of a single dialogue line are sent to the Cloud backend."
              + " Cloud TTS is billed per character, so a positive cap truncates an unusually long"
              + " line at a sentence or word boundary before sending. 0 (default) sends the whole"
              + " line uncapped; OSRS lines are short, so set a cap only to bound pathological"
              + " cases.",
      position = 1,
      section = advancedSection)
  @Range(min = 0, max = 5000)
  default int cloudMaxChars() {
    return 0;
  }

  @ConfigItem(
      keyName = "debugMode",
      name = "Debug Logging",
      description = "Show detailed NPC race/gender resolution info in the client logs.",
      position = 2,
      section = advancedSection)
  default boolean debugMode() {
    return false;
  }
}
