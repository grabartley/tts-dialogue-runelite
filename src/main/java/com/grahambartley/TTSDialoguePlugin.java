package com.grahambartley;

import com.google.inject.Provides;
import com.grahambartley.tts.KokoroAudio;
import com.grahambartley.tts.KokoroTtsEngine;
import java.nio.file.Path;
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
import net.runelite.client.RuneLite;
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
  private final Object audioLock = new Object();

  private VoiceManager voiceManager;

  private KokoroTtsEngine ttsEngine;

  @Override
  protected void startUp() {
    voiceManager = new VoiceManager(config, client);

    Path ttsDir = RuneLite.RUNELITE_DIR.toPath().resolve("tts-dialogue");
    ttsEngine = new KokoroTtsEngine(ttsDir);
    // Warm the model on a background thread so the first line is not the one that pays the load.
    ttsEngine.prewarm();

    log.info("TTSDialogue started");
  }

  @Override
  protected void shutDown() {
    if (ttsEngine != null) {
      ttsEngine.close();
      ttsEngine = null;
    }
    log.info("TTS Plugin stopped");
  }

  /** Synthesizes the line in-process with Kokoro, off the game thread, then plays it back. */
  private void speakWithTTS(String text, String speaker, String npcName) {
    if (ttsEngine == null || ttsEngine.isFailed()) {
      return;
    }
    int speakerId = voiceManager.getSpeakerId(speaker, npcName);
    long requestedAtNanos = System.nanoTime();
    ttsEngine.speak(
        text,
        speakerId,
        pcm -> {
          long latencyMs = (System.nanoTime() - requestedAtNanos) / 1_000_000L;
          log.info(
              "In-process TTS end-to-end latency: {} ms for \"{}\"", latencyMs, abbreviate(text));
          playAudio(KokoroAudio.toAudioInputStream(pcm.getSamples(), pcm.getSampleRate()));
        });
  }

  private static String abbreviate(String text) {
    return text.length() <= 40 ? text : text.substring(0, 40) + "...";
  }

  private void playAudio(AudioInputStream audioStream) {
    // Playback is triggered from the TTS background thread, and onGameTick stops the clip on
    // skipped dialogue, so guard the shared clip.
    synchronized (audioLock) {
      try {
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
      } finally {
        try {
          audioStream.close();
        } catch (Exception ignored) {
          // nothing to do on close failure
        }
      }
    }
  }

  private String cleanDialogueText(String raw) {
    return raw.replaceAll("<[^>]+>", "").trim();
  }

  /** Extracts NPC name from dialogue widget or uses current interacting NPC. */
  private String getCurrentNPCName() {
    // Try to get NPC name from dialogue name widget
    Widget npcNameWidget = client.getWidget(WidgetInfo.DIALOG_NPC_NAME);
    if (npcNameWidget != null && !npcNameWidget.isHidden()) {
      String npcName = npcNameWidget.getText();
      if (npcName != null && !npcName.isEmpty()) {
        return npcName.trim();
      }
    }

    // Fallback: try to get from interacting NPC
    if (client.getLocalPlayer() != null && client.getLocalPlayer().getInteracting() != null) {
      String interactingName = client.getLocalPlayer().getInteracting().getName();
      if (interactingName != null && !interactingName.isEmpty()) {
        return interactingName.trim();
      }
    }

    // Last resort: return "Unknown NPC"
    return "Unknown NPC";
  }

  @Subscribe
  public void onGameTick(final GameTick tick) {
    Widget npcDialogue = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
    if (npcDialogue != null && !npcDialogue.isHidden()) {
      String text = npcDialogue.getText();
      if (text != null && !text.isEmpty() && !text.equals(lastSpoken)) {
        lastSpoken = text;
        String cleaned = cleanDialogueText(text);
        String npcName = getCurrentNPCName();
        speakWithTTS(cleaned, "npc", npcName);
      }
    }

    Widget playerDialogue = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
    if (playerDialogue != null && !playerDialogue.isHidden()) {
      String text = playerDialogue.getText();
      if (text != null && !text.isEmpty() && !text.equals(lastSpoken)) {
        lastSpoken = text;
        String cleaned = cleanDialogueText(text);
        speakWithTTS(cleaned, "player", null); // No NPC name needed for player
      }
    }

    if ((npcDialogue == null || npcDialogue.isHidden())
        && (playerDialogue == null || playerDialogue.isHidden())) {
      if (ttsEngine != null) {
        ttsEngine.interrupt();
      }
      synchronized (audioLock) {
        if (currentClip != null && currentClip.isRunning()) {
          currentClip.stop();
          currentClip.close();
        }
      }
      lastSpoken = "";
    }
  }

  @Provides
  TTSDialogueConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(TTSDialogueConfig.class);
  }
}
