package com.grahambartley;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("ttsDialogue")
public interface TTSDialogueConfig extends Config
{
  @ConfigItem(
      keyName = "volume",
      name = "Dialogue Volume",
      description = "Volume of the spoken dialogue (0–100)",
      position = 0
  )
  default int volume()
  {
    return 100;
  }
}