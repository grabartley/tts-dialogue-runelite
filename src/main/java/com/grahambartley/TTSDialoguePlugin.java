package com.grahambartley;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@PluginDescriptor(
	name = "TTSDialogue"
)
public class TTSDialoguePlugin extends Plugin
{
	private final Client client;

	private String lastSpoken = "";

	@Inject
  public TTSDialoguePlugin(final Client client) {
    this.client = client;
  }

  @Override
	protected void startUp()
	{
		log.info("TTSDialogue started!");
	}

	@Override
	protected void shutDown()
	{
		log.info("TTS Plugin stopped");
	}

	private void speakWithTTS(String text)
	{
		try
		{
			URL url = new URL("http://localhost:59125/");
			HttpURLConnection con = (HttpURLConnection) url.openConnection();
			con.setRequestMethod("POST");
			con.setRequestProperty("Content-Type", "text/plain");
			con.setDoOutput(true);

			try (OutputStream os = con.getOutputStream();
					 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8)))
			{
				writer.write(text);
				writer.flush();
			}

			InputStream is = con.getInputStream();
			Path tempPath = Files.createTempFile("npc_voice", ".wav");
			Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);

			playAudio(tempPath.toString());
			con.disconnect();
		}
		catch (Exception e)
		{
			log.warn("TTS failed: " + e.getMessage());
		}
	}

	private void playAudio(String filepath)
	{
		try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(new File(filepath)))
		{
			Clip clip = AudioSystem.getClip();
			clip.open(audioStream);
			clip.start();
		}
		catch (Exception e)
		{
			log.warn("Audio playback failed: " + e.getMessage());
		}
	}

	private String cleanDialogueText(String raw)
	{
		return raw.replaceAll("<[^>]+>", "").trim();
	}

	@Subscribe
	public void onGameTick(final GameTick tick)
	{
		Widget npcDialogue = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
		if (npcDialogue != null && !npcDialogue.isHidden())
		{
			String text = npcDialogue.getText();
			if (text != null && !text.isEmpty() && !text.equals(lastSpoken))
			{
				lastSpoken = text;
				String cleaned = cleanDialogueText(text);
				speakWithTTS(cleaned);
			}
		}
	}
}
