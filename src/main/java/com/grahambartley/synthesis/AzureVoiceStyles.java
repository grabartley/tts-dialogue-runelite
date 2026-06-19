package com.grahambartley.synthesis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Records, per Azure neural voice, which {@code mstts:express-as} styles it actually supports.
 *
 * <p>Azure rejects (or silently ignores) a style a voice does not expose, so {@link AzureSsml} only
 * emits a style this table confirms. The style names here are the four emotional styles the plugin
 * maps from {@link Emotion}: {@code cheerful}, {@code sad}, {@code angry}, {@code terrified}. A
 * voice absent from the table, or present without a requested style, degrades to plain delivery.
 */
final class AzureVoiceStyles {

  private static final Map<String, Set<String>> SUPPORTED = new HashMap<>();

  private AzureVoiceStyles() {}

  private static void register(String voice, String... styles) {
    SUPPORTED.put(voice, new HashSet<>(Arrays.asList(styles)));
  }

  static {
    // Voices used by AzureVoiceMap, each with the styles the plugin may request. These mirror the
    // multi-style support documented for the Azure neural voice catalogue; voices kept here all
    // expose the full {cheerful, sad, angry, terrified} set the plugin uses.
    String[] fullEmotionVoices = {
      "en-US-AriaNeural",
      "en-US-GuyNeural",
      "en-US-JaneNeural",
      "en-US-DavisNeural",
      "en-US-TonyNeural",
      "en-US-AshleyNeural",
      "en-US-JasonNeural",
      "en-US-NancyNeural",
      "en-US-SaraNeural",
      "en-US-MonicaNeural",
      "en-US-CoraNeural",
    };
    for (String voice : fullEmotionVoices) {
      register(voice, "cheerful", "sad", "angry", "terrified");
    }

    // British voices expose a narrower set; only the styles confirmed below are emitted, the rest
    // degrade to plain so Azure never receives an unsupported style.
    register("en-GB-RyanNeural", "cheerful", "sad");
    register("en-GB-SoniaNeural", "cheerful", "sad");
    register("en-GB-ThomasNeural");
    register("en-GB-LibbyNeural");
    register("en-GB-AlfieNeural");

    // US voices used for undead/demon males with confirmed style support.
    register("en-US-ChristopherNeural", "cheerful", "sad", "angry");
    register("en-US-BrandonNeural", "cheerful", "sad", "angry");
  }

  /** Whether the given voice supports the given Azure style name. */
  static boolean supports(String voice, String style) {
    Set<String> styles = SUPPORTED.get(voice);
    return styles != null && styles.contains(style);
  }
}
