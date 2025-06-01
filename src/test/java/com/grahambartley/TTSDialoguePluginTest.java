package com.grahambartley;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class TTSDialoguePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TTSDialoguePlugin.class);
		RuneLite.main(args);
	}
}