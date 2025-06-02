package com.grahambartley;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("ttsDialogue")
public interface TTSDialogueConfig extends Config {
  @ConfigItem(
      keyName = "volume",
      name = "Dialogue Volume",
      description = "Volume of the spoken dialogue (0–100)",
      position = 0)
  @Range(min = 0, max = 100)
  default int volume() {
    return 100;
  }
}
