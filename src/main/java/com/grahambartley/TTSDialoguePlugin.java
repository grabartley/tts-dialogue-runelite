package com.grahambartley;

import com.google.inject.Provides;
import com.grahambartley.tts.KokoroAudio;
import com.grahambartley.tts.KokoroTtsEngine;
import java.io.BufferedWriter;
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

  // Speaker ids into the Kokoro voice bank. Race/gender mapping is a separate issue, so for now the
  // player and NPCs just get two distinct built-in voices.
  private static final int NPC_SPEAKER_ID = 0;
  private static final int PLAYER_SPEAKER_ID = 1;

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
    if (config.useInProcessTts()) {
      // Warm the model on a background thread so the first line is not the one that pays the load.
      ttsEngine.prewarm();
    }

    log.info("TTSDialogue started");

    // Show server health status if configured
    if (config.showServerStatus()) {
      logServerHealthStatus();
    }
  }

  @Override
  protected void shutDown() {
    if (ttsEngine != null) {
      ttsEngine.close();
      ttsEngine = null;
    }
    log.info("TTS Plugin stopped");
  }

  private void speakWithTTS(String text, String speaker, String npcName) {
    if (config.useInProcessTts()) {
      speakInProcess(text, speaker);
      return;
    }
    speakWithHttpServer(text, speaker, npcName);
  }

  /** Synthesizes the line in-process with Kokoro, off the game thread, then plays it back. */
  private void speakInProcess(String text, String speaker) {
    if (ttsEngine == null || ttsEngine.isFailed()) {
      return;
    }
    int speakerId = "player".equalsIgnoreCase(speaker) ? PLAYER_SPEAKER_ID : NPC_SPEAKER_ID;
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

  private void speakWithHttpServer(String text, String speaker, String npcName) {
    try {
      String port;
      if (config.enableRaceBasedVoices() && voiceManager != null) {
        port = voiceManager.getPortForSpeaker(speaker, npcName);
      } else {
        // Fallback to original behavior
        port = speaker.equalsIgnoreCase("player") ? "59126" : "59125";
      }

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

      playAudio(AudioSystem.getAudioInputStream(tempPath.toFile()));
      con.disconnect();

      if (config.enableRaceBasedVoices() && !"player".equalsIgnoreCase(speaker)) {
        log.debug("Used voice for NPC '{}': port {}", npcName, port);
      }
    } catch (Exception e) {
      log.warn("TTS failed: " + e.getMessage());
    }
  }

  private void playAudio(AudioInputStream audioStream) {
    // Playback can be triggered from the game thread (HTTP path) or the TTS background thread
    // (in-process path), and onGameTick stops the clip on skipped dialogue, so guard the shared
    // clip.
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

  /** Extracts NPC name from dialogue widget or uses current interacting NPC */
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

  /** Log the health status of all TTS voice servers */
  private void logServerHealthStatus() {
    if (voiceManager == null) {
      return;
    }

    log.info("🎭 TTS Voice Server Health Status:");
    log.info("====================================");

    var healthStatus = voiceManager.getServerHealthStatus();
    int healthyCount = 0;
    int totalCount = healthStatus.size();

    for (var entry : healthStatus.entrySet()) {
      VoiceManager.VoiceProfile voice = entry.getKey();
      boolean isHealthy = entry.getValue();

      String status = isHealthy ? "✅ HEALTHY" : "❌ UNAVAILABLE";
      log.info("  {} (port {}): {}", voice.getDisplayName(), voice.getPort(), status);

      if (isHealthy) {
        healthyCount++;
      }
    }

    log.info("====================================");
    log.info("📊 Summary: {}/{} voice servers are healthy", healthyCount, totalCount);

    if (healthyCount == 0) {
      log.warn(
          "⚠️  No TTS servers are running! Please start voice servers using './setup-voices.sh start'");
    } else if (healthyCount < totalCount) {
      log.warn("⚠️  Some TTS servers are unavailable. Fallback voices will be used when needed.");
      if (config.enableFallbacks()) {
        log.info("💡 Voice fallbacks are enabled - missing voices will use alternatives");
      } else {
        log.warn("⚠️  Voice fallbacks are disabled - some dialogue may fail");
      }
    } else {
      log.info("🎉 All voice servers are healthy!");
    }
  }

  @Provides
  TTSDialogueConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(TTSDialogueConfig.class);
  }
}
