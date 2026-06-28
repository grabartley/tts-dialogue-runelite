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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
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
   * any of these at runtime must re-run the newly selected backend's off-thread install/spawn so it
   * is warm before the next line rather than starting cold on it. {@code voiceBackend} switches the
   * selection; {@code openRouterApiKey} lets a previously-unavailable Cloud selection become
   * available once a key is entered.
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

  @Inject private ConfigManager configManager;

  @Inject private ClientThread clientThread;

  /**
   * Hidden persisted flag marking that the first-run onboarding guide has been shown, so it appears
   * exactly once ever rather than every login. Not a {@link ConfigItem} (nothing for the user to
   * toggle); read and set directly through {@link ConfigManager}.
   */
  static final String ONBOARDING_SEEN_KEY = "onboardingSeen";

  /** Chat-markup hex colour for plugin notices, so they stand out red from ordinary game chat. */
  private static final String CHAT_NOTICE_COLOR = "ff3333";

  /**
   * Per-session guard so the onboarding check runs at most once per plugin lifetime, not per tick.
   */
  private boolean onboardingChecked;

  private String lastSpoken = "";

  /**
   * Whether a dialogue widget (NPC or player text) was open on the previous tick. Used to
   * edge-trigger the close interrupt so audio is cut only on the open-&gt;closed transition, not on
   * every idle tick, which would otherwise truncate public-chat clips played while walking around.
   */
  private boolean wasDialogueOpen;

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

  /**
   * Warms the audio cache for the dialogue options the player can currently see, so the line picked
   * next plays from cache. Off-thread behind {@link DialogueAudioService#prefetch}; gated by {@code
   * prefetch}; reset on dialogue close. Null until {@link #startUp}.
   */
  private DialoguePrefetcher prefetcher;

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
            config::autoLearnNewNpcs);
    voiceManager.enableLearning(learnedStore, learningService);
    // The local Kokoro backend now runs the engine as an external --stdio process. The installer
    // resolves the per-OS bundle from the bundled manifest and downloads it (off the game thread,
    // lazily on warm-up); the client spawns and drives that process. No model or native libs ship
    // in the plugin jar.
    EngineInstaller installer = new EngineInstaller(okHttpClient, gson, ttsDir.resolve("engines"));
    LocalKokoroBackend localKokoro =
        new LocalKokoroBackend(installer, launcher -> new ExternalEngineClient(launcher, gson));
    // The cloud OpenRouter backend is registered alongside the local Kokoro backend and selected
    // when Voice Backend is Cloud and an API key is set. It uses the injected OkHttpClient and Gson
    // (Hub rule: never new them in plugin code). The provider routes every line to the selected
    // backend and applies the emotion-downgrade rule; the two backends stay strictly separate.
    OpenRouterTtsBackend cloudBackend = new OpenRouterTtsBackend(okHttpClient, config, gson);
    cloudBackend.setNotice(this::notifyBackend);
    backendProvider = new BackendProvider(config, localKokoro, cloudBackend);
    // Persistent on-disk cache lives under the same RuneLite dir as the engine; on by default so
    // repeated lines survive restarts and cloud backends are not re-billed. It sits in front of the
    // backend provider's synthesis regardless of which backend (local or cloud) runs. Opt-out via
    // config.
    DiskAudioCache diskCache =
        config.persistentCache()
            ? new DiskAudioCache(ttsDir.resolve("cache"), config.cacheSizeLimitMiB() * 1024L * 1024)
            : null;
    audioService =
        new DialogueAudioService(
            backendProvider,
            new AudioPlayer(),
            diskCache,
            CACHE_SIZE,
            QUEUE_CAPACITY,
            config::volume);
    // Warm only the selected backend off the game thread so the first line is not the one that pays
    // the install/spawn (Cloud) or model-load (Local) cost, and the game thread never blocks on it.
    // Selecting Cloud never warms the local engine, and selecting Local never reaches the cloud.
    audioService.prewarm(backendProvider::warmUpActive);
    // Speculative prefetch warms the cache for the dialogue options the player can see; it shares
    // the audio service's dedup and both cache tiers, runs off the game thread, and is gated by the
    // prefetch config (read live, so toggling it takes effect immediately).
    prefetcher =
        new DialoguePrefetcher(
            audioService::prefetch, audioService::cancelPrefetch, config::prefetch);

    log.info("TTSDialogue started");
  }

  @Override
  protected void shutDown() {
    prefetcher = null;
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
   * Surfaces a one-time cloud-backend notice (e.g. "add an OpenRouter API key") to the player. The
   * message text is already user-facing: logged at warn for the client log, and echoed into game
   * chat so a player who never opens the log still sees it. Fired from a backend thread, so the
   * chat write is hopped onto the client thread.
   */
  private void notifyBackend(String message) {
    log.warn(message);
    clientThread.invokeLater(() -> addGameMessage(message));
  }

  /**
   * Shows the first-run onboarding guide exactly once, gated by the persisted {@link
   * #ONBOARDING_SEEN_KEY} flag and a per-session guard. Called from {@link #onGameTick}, so it runs
   * on the game thread with a live client; the chat write is therefore safe without a thread hop.
   */
  private void maybeShowOnboarding() {
    if (onboardingChecked) {
      return;
    }
    onboardingChecked = true;
    Boolean seen = configManager.getConfiguration(CONFIG_GROUP, ONBOARDING_SEEN_KEY, Boolean.class);
    if (!shouldShowOnboarding(seen)) {
      return;
    }
    addGameMessage(
        "Voiced Dialogue is on. The Cloud voice (recommended) needs a free OpenRouter API key: get"
            + " one at openrouter.ai and paste it into the plugin's Cloud Voice settings. While Cloud"
            + " is active your dialogue text is sent to OpenRouter to be voiced. Prefer to stay"
            + " offline? Set Voice Backend to Local for a free, no-key voice (basic and"
            + " neutral-only).");
    configManager.setConfiguration(CONFIG_GROUP, ONBOARDING_SEEN_KEY, true);
  }

  /**
   * Pure decision for {@link #maybeShowOnboarding}: show the guide unless the persisted seen flag
   * is already true. A {@code null} flag (never set) means a fresh install, so the guide shows.
   * Package-private so it is unit-testable without RuneLite injection.
   */
  static boolean shouldShowOnboarding(Boolean seenFlag) {
    return !Boolean.TRUE.equals(seenFlag);
  }

  /**
   * Posts a single red, plugin-tagged notice into the game chat box. Red marks it as a plugin
   * notice that stands out from ordinary dialogue and game spam. Must be called on the client
   * thread.
   */
  private void addGameMessage(String message) {
    String line = "<col=" + CHAT_NOTICE_COLOR + ">[Voiced Dialogue] " + message + "</col>";
    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", line, null);
  }

  /**
   * Hands the line to the off-thread synth + playback pipeline; never blocks the game thread. The
   * caller passes the speaker's chat-head expression animation id (or {@link #NO_EXPRESSION} when
   * there is no head); it is resolved to an {@link Emotion} here and ridden into the request.
   */
  private void speakWithTTS(String text, String speaker, String npcName, int headAnimationId) {
    Emotion emotion = resolveLineEmotion(headAnimationId, config.cloudEmotion());
    if (config.debugMode()) {
      log.info("[TTS voice] resolved emotion {} for head animation {}", emotion, headAnimationId);
    }
    VoiceSpec voice = voiceManager.resolveVoice(speaker, npcName);
    dispatch(new SynthesisRequest(text, voice, emotion, resolveProfile(speaker, npcName)));
  }

  /**
   * Voices the player's own public chat through the same player voice path as their dialogue lines,
   * but always neutral (public chat has no chat-head to read an expression from) and with
   * translation/global-quirk bypassed ({@code skipTranslation}), so chat is spoken exactly as
   * typed. Reuses {@link #resolveProfile} and {@link #dispatch}, so it shares the availability
   * guard, profile resolution, and cache/queue behaviour with dialogue.
   */
  private void speakPublicChat(String text) {
    VoiceSpec voice = voiceManager.resolveVoice("player", null);
    dispatch(
        new SynthesisRequest(
            text,
            voice,
            Emotion.NEUTRAL,
            resolveProfile("player", null),
            /* skipTranslation= */ true));
  }

  /**
   * Resolves the per-speaker {@link CharacterProfile} when character profiles are enabled, else
   * {@code null}. A null profile keeps the request (and its cache key) byte-for-byte identical to
   * the pre-profile behaviour. Shared by every synthesis path (dialogue, prefetch, public chat).
   */
  private CharacterProfile resolveProfile(String speaker, String npcName) {
    return config.cloudCharacterProfiles() ? voiceManager.resolveProfile(speaker, npcName) : null;
  }

  /**
   * Hands a built request to the off-thread synth pipeline, guarded by the same availability check
   * every speak path needs: no-op when the plugin is mid-shutdown ({@code audioService} null) or
   * the active backend is unavailable.
   */
  private void dispatch(SynthesisRequest request) {
    if (audioService == null || !backendProvider.active().isAvailable()) {
      return;
    }
    audioService.speak(request);
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
    maybeShowOnboarding();
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

    Widget options = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
    boolean optionsVisible = options != null && !options.isHidden();
    if (optionsVisible) {
      prefetchVisibleOptions(options);
    }

    // Edge-trigger the close interrupt: cut dialogue audio (and reset dedup) only on the
    // open->closed transition, not on every idle tick. Public chat plays with no dialogue open, so
    // interrupting every idle tick would truncate it within one tick (<=600ms).
    boolean dialogueOpen =
        (npcDialogue != null && !npcDialogue.isHidden())
            || (playerDialogue != null && !playerDialogue.isHidden());
    if (shouldInterruptOnClose(dialogueOpen, wasDialogueOpen)) {
      if (audioService != null) {
        audioService.interrupt();
      }
      lastSpoken = "";
    }
    wasDialogueOpen = dialogueOpen;

    // Reset prefetch only when the dialogue is fully gone (no text and no option list), so the
    // session cap and queued warming survive the option-select screen instead of being cancelled
    // and re-cancelled every tick while the player is choosing.
    boolean fullyClosed =
        (npcDialogue == null || npcDialogue.isHidden())
            && (playerDialogue == null || playerDialogue.isHidden())
            && !optionsVisible;
    if (fullyClosed && prefetcher != null) {
      prefetcher.reset();
    }
  }

  /**
   * Pure decision for {@link #onGameTick}'s close interrupt: cut audio only on the open-&gt;closed
   * transition, so the idle ticks while the player walks around (no dialogue open) never interrupt
   * a playing public-chat clip. Factored out so it is unit-testable without a live client.
   */
  static boolean shouldInterruptOnClose(boolean dialogueOpen, boolean wasDialogueOpen) {
    return wasDialogueOpen && !dialogueOpen;
  }

  /**
   * Voices the local player's own public chat (default off). Only the player's {@code PUBLICCHAT}
   * stream is spoken; other players' public messages, and every other chat type, are ignored. The
   * message is cleaned with the same {@link #cleanDialogueText} as dialogue (stripping rank icons,
   * colour and image tags) and voiced through the player path with translation bypassed.
   */
  @Subscribe
  public void onChatMessage(ChatMessage event) {
    if (!config.voicePublicChat()) {
      return;
    }
    if (event.getType() != ChatMessageType.PUBLICCHAT) {
      return;
    }
    Player local = client.getLocalPlayer();
    if (local == null || !isSelfPublicChat(event.getName(), local.getName())) {
      return;
    }
    String cleaned = cleanDialogueText(event.getMessage());
    if (cleaned.isEmpty()) {
      return;
    }
    speakPublicChat(cleaned);
  }

  /**
   * Whether a public-chat event came from the local player. Both names are run through {@link
   * Text#sanitize} first because {@code event.getName()} can carry clan/friend rank {@code
   * <img=...>} icons and non-breaking spaces that the local player's raw name does not. Pure and
   * null-safe (a null local name never matches) so it is unit-testable without a live client.
   */
  static boolean isSelfPublicChat(String eventName, String localName) {
    if (eventName == null || localName == null) {
      return false;
    }
    return Text.sanitize(eventName).equals(Text.sanitize(localName));
  }

  /**
   * Warms the cache for the dialogue options the player can currently see. Each option's text is
   * the line the player will speak if it is picked, so it is built into the exact same {@link
   * SynthesisRequest} (player voice, player profile, neutral) that {@link #speakWithTTS} would
   * produce for that line, and handed to the off-thread prefetcher. The known "Select an Option"
   * header and any blank rows are skipped. Only touches the client on the game thread; never
   * throws.
   */
  private void prefetchVisibleOptions(Widget options) {
    if (audioService == null || prefetcher == null || !config.prefetch()) {
      return;
    }
    if (!backendProvider.active().isAvailable()) {
      return;
    }
    Widget[] children = options.getDynamicChildren();
    if (children == null || children.length == 0) {
      return;
    }
    VoiceSpec voice = voiceManager.resolveVoice("player", null);
    CharacterProfile profile = resolveProfile("player", null);
    List<SynthesisRequest> candidates = new ArrayList<>(children.length);
    for (Widget child : children) {
      if (child == null) {
        continue;
      }
      String raw = child.getText();
      if (raw == null) {
        continue;
      }
      String cleaned = cleanDialogueText(raw);
      if (cleaned.isEmpty() || "Select an Option".equalsIgnoreCase(cleaned)) {
        continue;
      }
      candidates.add(new SynthesisRequest(cleaned, voice, Emotion.NEUTRAL, profile));
    }
    prefetcher.offer(candidates);
  }

  /**
   * Warms up the newly selected backend off the game thread when a backend-affecting config key
   * changes at runtime, so switching Voice Backend (or entering an OpenRouter key) installs /
   * spawns / handshakes the engine immediately rather than starting cold on the next line.
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
