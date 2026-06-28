package com.grahambartley;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.grahambartley.data.LearnedNpcStore;
import com.grahambartley.data.NpcLearningService;
import com.grahambartley.data.WikiNpcClient;
import com.grahambartley.synthesis.BackendProvider;
import com.grahambartley.synthesis.CharacterProfile;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.ExpressionEmotionTable;
import com.grahambartley.synthesis.LocalKokoroBackend;
import com.grahambartley.synthesis.OpenRouterTtsBackend;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import com.grahambartley.synthesis.engine.EngineInstaller;
import com.grahambartley.synthesis.engine.ExternalEngineClient;
import com.grahambartley.tts.AudioPlayer;
import com.grahambartley.tts.DialogueAudioService;
import com.grahambartley.tts.DiskAudioCache;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import net.runelite.client.events.ConfigChanged;
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

  /**
   * The {@code @ConfigGroup} value of {@link TTSDialogueConfig}. {@link ConfigChanged} events for
   * any other group are ignored.
   */
  static final String CONFIG_GROUP = "ttsDialogue";

  /**
   * Config keys that change which backend is selected or whether it can become available. Changing
   * any of these at runtime must re-run the active backend's off-thread install/spawn so a newly
   * selected cloud backend warms up instead of pre-emptively falling back to local Kokoro. {@code
   * voiceBackend} switches the selection; {@code openRouterApiKey} lets a previously-unavailable
   * Cloud selection become available once a key is entered.
   */
  private static final java.util.Set<String> WARM_TRIGGER_KEYS =
      java.util.Set.of("voiceBackend", "openRouterApiKey");

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

  /** Dedicated daemon thread for off-game-thread wiki NPC lookups (the auto-learn fallback). */
  private ExecutorService wikiExecutor;

  @Override
  protected void startUp() {
    voiceManager = new VoiceManager(config, client);

    Path ttsDir = RuneLite.RUNELITE_DIR.toPath().resolve("tts-dialogue");
    // Runtime "learn a new NPC" fallback: the learned cache is always consulted (so previously
    // learned NPCs voice correctly even with the toggle off), while new wiki lookups are gated by
    // the config toggle. Lookups run on a dedicated daemon thread, never the game thread.
    LearnedNpcStore learnedStore = new LearnedNpcStore(ttsDir.resolve("learned-npcs.json"), gson);
    wikiExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "tts-wiki-learn");
              t.setDaemon(true);
              return t;
            });
    NpcLearningService learningService =
        new NpcLearningService(
            new WikiNpcClient(okHttpClient, gson),
            learnedStore,
            wikiExecutor,
            config::wikiLookupFallback);
    voiceManager.enableLearning(learnedStore, learningService);
    // The local Kokoro backend now runs the engine as an external --stdio process. The installer
    // resolves the per-OS bundle from the bundled manifest and downloads it (off the game thread,
    // lazily on warm-up); the client spawns and drives that process. No model or native libs ship
    // in the plugin jar.
    EngineInstaller installer = new EngineInstaller(okHttpClient, gson, ttsDir.resolve("engines"));
    LocalKokoroBackend localKokoro =
        new LocalKokoroBackend(installer, launcher -> new ExternalEngineClient(launcher, gson));
    // The cloud OpenRouter backend is registered alongside the local Kokoro fallback and selected
    // when Voice Backend is Cloud and an API key is set. It uses the injected OkHttpClient and Gson
    // (Hub rule: never new them in plugin code).
    // The provider routes every line, applies the emotion-downgrade rule, and falls back to local
    // Kokoro (with a one-time notice) when the selected backend is unavailable.
    OpenRouterTtsBackend cloudBackend = new OpenRouterTtsBackend(okHttpClient, config, gson);
    cloudBackend.setNotice(this::notifyBackend);
    backendProvider = new BackendProvider(config, localKokoro, cloudBackend);
    backendProvider.setAvailabilityNotice(this::notifyBackend);
    // Persistent on-disk cache lives under the same RuneLite dir as the engine; on by default so
    // repeated lines survive restarts and cloud backends are not re-billed. It sits in front of the
    // backend provider's synthesis regardless of which backend (local or cloud) runs. Opt-out via
    // config.
    DiskAudioCache diskCache =
        config.persistentCache()
            ? new DiskAudioCache(ttsDir.resolve("cache"), config.diskCacheMaxMiB() * 1024L * 1024)
            : null;
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
    // Also warm the selected backend off the game thread so a Cloud selection prepares before the
    // first line and becomes available instead of pre-emptively falling back. A no-op when the
    // selection is already the local Kokoro fallback.
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
    if (wikiExecutor != null) {
      wikiExecutor.shutdownNow();
      wikiExecutor = null;
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
      log.info("[TTS voice] resolved emotion {} for head animation {}", emotion, headAnimationId);
    }
    // Character profile steers accent/style/pace and is rendered only by the cloud backend; emotion
    // still layers on top. Resolved only when enabled, so a null profile keeps the request (and its
    // cache key) byte-for-byte identical to the pre-profile behaviour.
    CharacterProfile profile =
        config.enableCharacterProfiles() ? voiceManager.resolveProfile(speaker, npcName) : null;
    audioService.speak(new SynthesisRequest(text, voice, emotion, profile));
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

  /**
   * Warms up the newly selected backend off the game thread when a backend-affecting config key
   * changes at runtime, so switching Voice Backend (or entering an OpenRouter key) installs /
   * spawns / handshakes the engine immediately instead of silently falling back to local Kokoro
   * until the next client restart.
   *
   * <p>The decision of <em>whether</em> a given event should warm lives in the pure, unit-testable
   * {@link #affectsBackendWarmUp} so it can be verified without RuneLite injection. The actual work
   * runs on the pipeline thread via {@link DialogueAudioService#prewarm}, the same hook {@link
   * #startUp} uses, so neither the game thread nor the config-dispatch thread blocks. No-ops safely
   * when the plugin is disabled or mid-shutdown ({@code audioService}/{@code backendProvider}
   * null). Re-running {@code warmUpActive} is idempotent: each backend's {@code warmUp} guards
   * itself, so an already-failed backend is not re-attempted within the session.
   */
  @Subscribe
  public void onConfigChanged(ConfigChanged event) {
    if (!affectsBackendWarmUp(event.getGroup(), event.getKey())) {
      return;
    }
    if (audioService == null || backendProvider == null) {
      return;
    }
    audioService.prewarm(backendProvider::warmUpActive);
  }

  /**
   * Pure decision logic for {@link #onConfigChanged}: returns {@code true} only when a changed
   * config entry belongs to this plugin's group ({@link #CONFIG_GROUP}) and its key affects backend
   * selection or availability ({@link #WARM_TRIGGER_KEYS}). Factored out so the warm-up trigger is
   * testable without RuneLite injection. Never throws; tolerates {@code null} group/key.
   */
  static boolean affectsBackendWarmUp(String group, String key) {
    // key != null first: WARM_TRIGGER_KEYS is an immutable Set.of(...), whose contains(null)
    // throws.
    return CONFIG_GROUP.equals(group) && key != null && WARM_TRIGGER_KEYS.contains(key);
  }

  @Provides
  TTSDialogueConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(TTSDialogueConfig.class);
  }
}
