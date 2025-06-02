package com.grahambartley;

import com.google.inject.Provides;
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
import javax.inject.Inject;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(name = "TTSDialogue")
public class TTSDialoguePlugin extends Plugin {
  @Inject private Client client;

  @Inject private TTSDialogueConfig config;

  private String lastSpoken = "";

  private Clip currentClip;

  @Override
  protected void startUp() {
    log.info("TTSDialogue started!");
  }

  @Override
  protected void shutDown() {
    log.info("TTS Plugin stopped");
  }

  private void speakWithTTS(String text, String speaker) {
    try {
      String port = speaker.equalsIgnoreCase("player") ? "59126" : "59125";
      URL url = new URL("http://localhost:" + port + "/");
      HttpURLConnection con = (HttpURLConnection) url.openConnection();
      con.setRequestMethod("POST");
      con.setRequestProperty("Content-Type", "text/plain");
      con.setDoOutput(true);

      try (OutputStream os = con.getOutputStream();
          BufferedWriter writer =
              new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
        writer.write(text);
        writer.flush();
      }

      InputStream is = con.getInputStream();
      Path tempPath = Files.createTempFile("npc_voice", ".wav");
      Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);

      playAudio(tempPath.toString());
      con.disconnect();
    } catch (Exception e) {
      log.warn("TTS failed: " + e.getMessage());
    }
  }

  private void playAudio(String filepath) {
    try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(new File(filepath))) {
      if (currentClip != null && currentClip.isRunning()) {
        currentClip.stop();
        currentClip.close();
      }

      Clip clip = AudioSystem.getClip();
      clip.open(audioStream);
      currentClip = clip;
      float volume = Math.max(0, Math.min(100, config.volume()));
      FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
      if (volume == 0) {
        gainControl.setValue(gainControl.getMinimum());
      } else {
        float gain = (float) (20f * Math.log10(volume / 100.0));
        gainControl.setValue(gain);
      }
      clip.start();
    } catch (Exception e) {
      log.warn("Audio playback failed: " + e.getMessage());
    }
  }

  private String cleanDialogueText(String raw) {
    return raw.replaceAll("<[^>]+>", "").trim();
  }

  @Subscribe
  public void onGameTick(final GameTick tick) {
    Widget npcDialogue = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
    if (npcDialogue != null && !npcDialogue.isHidden()) {
      String text = npcDialogue.getText();
      if (text != null && !text.isEmpty() && !text.equals(lastSpoken)) {
        lastSpoken = text;
        String cleaned = cleanDialogueText(text);
        speakWithTTS(cleaned, "npc");
      }
    }

    Widget playerDialogue = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
    if (playerDialogue != null && !playerDialogue.isHidden()) {
      String text = playerDialogue.getText();
      if (text != null && !text.isEmpty() && !text.equals(lastSpoken)) {
        lastSpoken = text;
        String cleaned = cleanDialogueText(text);
        speakWithTTS(cleaned, "player");
      }
    }

    if ((npcDialogue == null || npcDialogue.isHidden())
        && (playerDialogue == null || playerDialogue.isHidden())) {
      if (currentClip != null && currentClip.isRunning()) {
        currentClip.stop();
        currentClip.close();
      }
      lastSpoken = "";
    }
  }

  @Provides
  TTSDialogueConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(TTSDialogueConfig.class);
  }
}
