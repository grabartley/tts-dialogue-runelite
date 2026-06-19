package com.grahambartley;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.grahambartley.synthesis.BackendProvider;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.LocalKokoroBackend;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import com.grahambartley.synthesis.engine.EngineInstaller;
import com.grahambartley.synthesis.engine.ExternalEngineClient;
import com.grahambartley.tts.AudioPlayer;
import com.grahambartley.tts.DialogueAudioService;
import com.grahambartley.tts.DiskAudioCache;
import java.nio.file.Path;
import javax.inject.Inject;
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
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(name = "TTSDialogue")
public class TTSDialoguePlugin extends Plugin {

  /** Cache enough recent lines that loops of NPC chatter replay instantly without re-synthesis. */
  private static final int CACHE_SIZE = 64;

  /** Tiny backlog so a burst of dialogue ticks never blocks the game thread on enqueue. */
  private static final int QUEUE_CAPACITY = 4;

  @Inject private Client client;

  @Inject private TTSDialogueConfig config;

  /**
   * Injected per Hub rules: never {@code new OkHttpClient()} / {@code new Gson()} in plugin code.
   */
  @Inject private OkHttpClient okHttpClient;

  @Inject private Gson gson;

  private String lastSpoken = "";

  private VoiceManager voiceManager;

  private BackendProvider backendProvider;

  private DialogueAudioService audioService;

  @Override
  protected void startUp() {
    voiceManager = new VoiceManager(config, client);

    Path ttsDir = RuneLite.RUNELITE_DIR.toPath().resolve("tts-dialogue");
    // The local Kokoro backend now runs the engine as an external --stdio process. The installer
    // resolves the per-OS bundle from the bundled manifest and downloads it (off the game thread,
    // lazily on warm-up); the client spawns and drives that process. No model or native libs ship
    // in the plugin jar.
    EngineInstaller installer = new EngineInstaller(okHttpClient, gson, ttsDir.resolve("engines"));
    LocalKokoroBackend localKokoro =
        new LocalKokoroBackend(installer, launcher -> new ExternalEngineClient(launcher, gson));
    backendProvider = new BackendProvider(config, localKokoro);
    // Persistent on-disk cache lives under the same RuneLite dir as the engine; on by default so
    // repeated lines survive restarts and cloud backends are not re-billed. It sits in front of the
    // backend provider's synthesis regardless of which backend (local or cloud) runs. Opt-out via
    // config.
    DiskAudioCache diskCache =
        config.persistentCache() ? new DiskAudioCache(ttsDir.resolve("cache")) : null;
    audioService =
        new DialogueAudioService(
            backendProvider,
            new AudioPlayer(),
            diskCache,
            CACHE_SIZE,
            QUEUE_CAPACITY,
            config::volume);
    // Install + spawn the engine on the pipeline thread so the first line is not the one that pays
    // the download/launch cost, and the game thread never blocks on it.
    audioService.prewarm(backendProvider::warmUpLocal);

    log.info("TTSDialogue started");
  }

  @Override
  protected void shutDown() {
    if (audioService != null) {
      audioService.close();
      audioService = null;
    }
    if (backendProvider != null) {
      backendProvider.close();
      backendProvider = null;
    }
    log.info("TTS Plugin stopped");
  }

  /** Hands the line to the off-thread synth + playback pipeline; never blocks the game thread. */
  private void speakWithTTS(String text, String speaker, String npcName) {
    if (audioService == null || !backendProvider.active().isAvailable()) {
      return;
    }
    VoiceSpec voice = voiceManager.resolveVoice(speaker, npcName);
    // Emotion detection (#26) is not wired yet, and it is suppressed entirely when disabled in
    // config; for now every line is neutral.
    Emotion emotion = Emotion.NEUTRAL;
    audioService.speak(new SynthesisRequest(text, voice, emotion));
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
      if (audioService != null) {
        audioService.interrupt();
      }
      lastSpoken = "";
    }
  }

  @Provides
  TTSDialogueConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(TTSDialogueConfig.class);
  }
}
