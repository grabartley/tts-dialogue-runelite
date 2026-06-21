package com.grahambartley;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.grahambartley.synthesis.AzureTtsBackend;
import com.grahambartley.synthesis.BackendProvider;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.ExpressionEmotionTable;
import com.grahambartley.synthesis.LocalKokoroBackend;
import com.grahambartley.synthesis.LocalZonosBackend;
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
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(name = "Voiced Dialogue")
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

  /**
   * Sentinel head-animation id meaning "no detectable expression" - a missing head widget (sprite /
   * objectbox dialogue) or the one-tick race where the head animation lags the text. Resolves to
   * {@link Emotion#NEUTRAL}, matching the engine's own {@code -1} for an idle head.
   */
  private static final int NO_EXPRESSION = -1;

  private VoiceManager voiceManager;

  /**
   * The bundled chathead-expression -> {@link Emotion} table (#25). Loaded once on start-up and
   * reused for every line; owns the {@code -1}/unmapped -> NEUTRAL contract.
   */
  private final ExpressionEmotionTable expressionEmotions = ExpressionEmotionTable.load();

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
    // The local GPU emotional backend (Zonos) is reached through the same external --stdio
    // transport
    // as Kokoro, but resolves a separate engine bundle from its own manifest. Selected when Voice
    // Backend is Local (GPU); available only when its engine installs, spawns, and reports a usable
    // GPU. The committed Zonos manifest is the dev placeholder, so until a GPU engine is published
    // this backend is unavailable and the provider falls back to local Kokoro.
    EngineInstaller zonosInstaller =
        new EngineInstaller(
            okHttpClient, gson, ttsDir.resolve("engines"), EngineInstaller.ZONOS_MANIFEST_RESOURCE);
    LocalZonosBackend localZonos =
        new LocalZonosBackend(zonosInstaller, launcher -> new ExternalEngineClient(launcher, gson));
    // The cloud Azure backend is registered alongside the local Kokoro fallback and selected when
    // Voice Backend is Cloud and a key/region are set. It uses the injected OkHttpClient (Hub
    // rule).
    // The provider routes every line, applies the emotion-downgrade rule, and falls back to local
    // Kokoro (with a one-time notice) when the selected backend is unavailable.
    AzureTtsBackend azureBackend = new AzureTtsBackend(okHttpClient, config);
    azureBackend.setNotice(this::notifyBackend);
    backendProvider = new BackendProvider(config, localKokoro, localZonos, azureBackend);
    backendProvider.setAvailabilityNotice(this::notifyBackend);
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
    // Also warm the selected backend off the game thread so a GPU (Zonos) selection runs its
    // install/spawn/handshake before the first line and becomes available instead of pre-emptively
    // falling back. A no-op when the selection is already the local Kokoro fallback.
    audioService.prewarm(backendProvider::warmUpActive);

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

  /**
   * Surfaces a one-time backend notice (fallback or cloud failure) to the player. Logged at warn so
   * it appears in the client log without a chat dependency; the message text is already
   * user-facing.
   */
  private void notifyBackend(String message) {
    log.warn(message);
  }

  /**
   * Hands the line to the off-thread synth + playback pipeline; never blocks the game thread. The
   * caller passes the speaker's chat-head expression animation id (or {@link #NO_EXPRESSION} when
   * there is no head); it is resolved to an {@link Emotion} here and ridden into the request.
   */
  private void speakWithTTS(String text, String speaker, String npcName, int headAnimationId) {
    if (audioService == null || !backendProvider.active().isAvailable()) {
      return;
    }
    VoiceSpec voice = voiceManager.resolveVoice(speaker, npcName);
    Emotion emotion = resolveLineEmotion(headAnimationId, config.enableEmotion());
    if (config.debugMode()) {
      log.debug("Resolved emotion {} for head animation {}", emotion, headAnimationId);
    }
    audioService.speak(new SynthesisRequest(text, voice, emotion));
  }

  /**
   * Pure decision logic for a line's emotion, factored out of the widget read so it is
   * unit-testable without a live client. Returns {@link Emotion#NEUTRAL} when emotion is disabled
   * in config or the animation id is {@code -1}/unmapped (missing head, sprite dialogue, non-human
   * head, or the one-tick race); otherwise the table's mapped emotion. Never returns {@code null}
   * and never throws.
   */
  Emotion resolveLineEmotion(int headAnimationId, boolean enableEmotion) {
    if (!enableEmotion) {
      return Emotion.NEUTRAL;
    }
    return expressionEmotions.resolve(headAnimationId);
  }

  /**
   * Reads the chat-head expression animation id from the given dialogue head widget id ({@code
   * InterfaceID.ChatLeft.HEAD} for NPC lines, {@code InterfaceID.ChatRight.HEAD} for player lines).
   * Returns {@link #NO_EXPRESSION} when the head widget is absent (sprite/objectbox dialogues have
   * no head), so the caller resolves NEUTRAL. Only touches the client on the game thread; never
   * throws.
   */
  private int readHeadAnimationId(int headWidgetId) {
    Widget head = client.getWidget(headWidgetId);
    if (head == null) {
      return NO_EXPRESSION;
    }
    return head.getAnimationId();
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
        int headAnimationId = readHeadAnimationId(InterfaceID.ChatLeft.HEAD);
        speakWithTTS(cleaned, "npc", npcName, headAnimationId);
      }
    }

    Widget playerDialogue = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
    if (playerDialogue != null && !playerDialogue.isHidden()) {
      String text = playerDialogue.getText();
      if (text != null && !text.isEmpty() && !text.equals(lastSpoken)) {
        lastSpoken = text;
        String cleaned = cleanDialogueText(text);
        int headAnimationId = readHeadAnimationId(InterfaceID.ChatRight.HEAD);
        // No NPC name needed for player lines.
        speakWithTTS(cleaned, "player", null, headAnimationId);
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
