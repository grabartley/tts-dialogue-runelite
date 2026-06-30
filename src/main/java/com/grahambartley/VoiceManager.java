package com.grahambartley;

import com.grahambartley.data.LearnedNpcStore;
import com.grahambartley.data.NPCAttributes;
import com.grahambartley.data.NPCDemographicAnalyzer;
import com.grahambartley.data.NpcLearningService;
import com.grahambartley.data.NpcProfileTable;
import com.grahambartley.synthesis.CharacterProfile;
import com.grahambartley.synthesis.VoiceSpec;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;

/**
 * Resolves an NPC (or the player) to a backend-neutral {@link VoiceSpec} and the per-speaker {@link
 * CharacterProfile}. A thin facade over focused collaborators: NPC lookup ({@link NpcFinder}),
 * voice resolution ({@link NpcVoiceResolver}), Kokoro speaker selection ({@link
 * KokoroSpeakerPool}), and trace formatting ({@link VoiceTraceFormatter}).
 *
 * <p>The spec carries the detected race and gender so the cloud backend can map them to its own
 * voice bank. The local Kokoro backend is British-only by design (#150): Kokoro bakes accent into
 * the chosen speaker and this is a British medieval fantasy world, so accents are a Cloud-only
 * feature. Local picks a voice from the {@link KokoroVoice} British bank by gender alone; race
 * never selects a Local voice. The plugin sends the chosen speaker id explicitly, so the engine
 * just renders it.
 */
@Slf4j
public class VoiceManager {

  /** Speaker-type tokens passed as the {@code speaker} argument to the resolve methods. */
  public static final String SPEAKER_PLAYER = "player";

  public static final String SPEAKER_NPC = "npc";

  public enum NPCRace {
    HUMAN,
    ELF,
    DWARF,
    GOBLIN,
    MONKEY,
    GORILLA,
    TROLL,
    UNDEAD,
    DEMON,
    WIZARD,
    TORTUGAN,
    UNKNOWN
  }

  public enum NPCGender {
    MALE,
    FEMALE,
    UNKNOWN
  }

  /**
   * The British Kokoro voice bank used for every Local line (British-only by design, #150). Kokoro
   * has no accent control, so in this British medieval fantasy world every voice is British and
   * accents are reserved for the cloud backend. The bank is small and shared across all races: race
   * never picks a Local voice, only gender does, with per-NPC variety coming from hashing into the
   * gender pool. The ids are the engine model's own speaker indices, sent on the wire verbatim.
   */
  public enum KokoroVoice {
    BM_DANIEL(24, "bm_daniel", NPCGender.MALE),
    BM_FABLE(25, "bm_fable", NPCGender.MALE),
    BM_GEORGE(26, "bm_george", NPCGender.MALE),
    BM_LEWIS(27, "bm_lewis", NPCGender.MALE),
    BF_ALICE(20, "bf_alice", NPCGender.FEMALE),
    BF_EMMA(21, "bf_emma", NPCGender.FEMALE),
    BF_ISABELLA(22, "bf_isabella", NPCGender.FEMALE);

    private final int speakerId;
    private final String voiceName;
    private final NPCGender gender;

    KokoroVoice(int speakerId, String voiceName, NPCGender gender) {
      this.speakerId = speakerId;
      this.voiceName = voiceName;
      this.gender = gender;
    }

    /** The Kokoro speaker id sent on the wire and rendered by the engine. */
    public int getSpeakerId() {
      return speakerId;
    }

    /** The underlying Kokoro voice name (for docs and logs). */
    public String getVoiceName() {
      return voiceName;
    }

    /** The gender this voice belongs to. */
    public NPCGender getGender() {
      return gender;
    }

    /** The voice name for a speaker id, or a bare {@code "id=<n>"} when outside the bank. */
    static String nameFor(int speakerId) {
      for (KokoroVoice voice : values()) {
        if (voice.speakerId == speakerId) {
          return voice.voiceName;
        }
      }
      return "id=" + speakerId;
    }
  }

  /**
   * The two selectable player voices, kept deliberately opaque ("Type A" / "Type B") so the config
   * exposes a simple either/or. Each just fixes the player's gender, which then drives the British
   * Local voice and the cloud voice.
   */
  public enum PlayerVoice {
    TYPE_A(NPCGender.MALE, "Type A"),
    TYPE_B(NPCGender.FEMALE, "Type B");

    private final NPCGender gender;
    private final String label;

    PlayerVoice(NPCGender gender, String label) {
      this.gender = gender;
      this.label = label;
    }

    /** The gender this player voice fixes for voice resolution on both backends. */
    public NPCGender getGender() {
      return gender;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private final TTSDialogueConfig config;
  private final NPCDemographicAnalyzer demographicAnalyzer;
  private final NpcProfileTable profileTable;
  private final NpcFinder npcFinder;
  private final NpcVoiceResolver npcVoiceResolver;

  public VoiceManager(TTSDialogueConfig config, Client client) {
    this.config = config;
    this.demographicAnalyzer = new NPCDemographicAnalyzer();
    this.demographicAnalyzer.initialize();
    this.profileTable = new NpcProfileTable();
    this.profileTable.initialize();
    this.npcFinder = new NpcFinder(client);
    this.npcVoiceResolver = new NpcVoiceResolver(config, demographicAnalyzer, npcFinder);
  }

  /**
   * Wires in the runtime "learn a new NPC" fallback: the analyzer consults {@code store} for NPCs
   * missing from the bundled table, and an unknown NPC triggers a one-off background wiki lookup
   * via {@code service} that populates {@code store} for subsequent lines.
   */
  public void enableLearning(LearnedNpcStore store, NpcLearningService service) {
    this.demographicAnalyzer.setLearnedStore(store);
    this.npcVoiceResolver.setLearningService(service);
  }

  /**
   * Resolves the {@link CharacterProfile} steering a line's delivery: the player's configured
   * profile for player lines, or the NPC profile built by combining every matching layer (default,
   * race, every keyword category that matches, and any per-NPC override) keyed on the NPC's
   * composition id and display name. Never returns {@code null}. Only the cloud backend renders the
   * profile; the local backend ignores it.
   */
  public CharacterProfile resolveProfile(String speaker, String npcName) {
    if (SPEAKER_PLAYER.equalsIgnoreCase(speaker)) {
      CharacterProfile profile =
          profileTable.resolvePlayer(
              config.playerAccent(), config.playerPersona(), config.playerPace());
      if (config.debugMode()) {
        log.info("[TTS profile] player -> '{}' accent='{}'", profile.name(), profile.accent());
      }
      return profile;
    }

    Integer npcId = null;
    String race = null;
    String ethnicity = null;
    NPC npc = npcFinder.findByName(npcName);
    if (npc != null) {
      npcId = npc.getId();
      NPCAttributes attributes = demographicAnalyzer.analyzeNPC(npc);
      if (attributes != null) {
        race = attributes.getRace();
        ethnicity = attributes.getEthnicity();
        // The id the analyzer actually matched (active or base), so a bespoke byId profile keyed by
        // the wiki id resolves even for transformed multiloc NPCs.
        npcId = attributes.getNpcId();
      }
    }

    NpcProfileTable.Resolution resolution =
        profileTable.resolveNpc(npcId, npcName, race, ethnicity);
    if (config.debugMode()) {
      log.info(
          "[TTS profile] npc='{}' id={} race={} ethnicity={} -> '{}' (source={}, accent='{}')",
          npcName,
          npcId == null ? "MISS" : npcId,
          race == null ? "UNKNOWN" : race,
          ethnicity == null ? "-" : ethnicity,
          resolution.profile().name(),
          resolution.source(),
          resolution.profile().accent());
    }
    return resolution.profile();
  }

  /**
   * Resolves a backend-neutral {@link VoiceSpec} for a line of dialogue. The player uses the gender
   * of the configured player voice; an NPC uses its detected race and gender. Both carry an
   * explicit British Kokoro speaker so the local engine renders that exact voice; the cloud backend
   * reads the race/gender and uses the speaker id only as a per-NPC variety seed.
   */
  public VoiceSpec resolveVoice(String speaker, String npcName) {
    if (SPEAKER_PLAYER.equalsIgnoreCase(speaker)) {
      NPCGender gender = playerGender();
      if (config != null && config.debugMode()) {
        log.info(VoiceTraceFormatter.buildPlayerTrace(gender));
      }
      return VoiceSpec.player(gender, KokoroSpeakerPool.playerSpeaker(gender));
    }
    return npcVoiceResolver.resolve(npcName);
  }

  /**
   * The Kokoro speaker id for a resolved {@link VoiceSpec}, used only by the local Kokoro backend.
   * The player resolves to its gender's British voice; an NPC keeps the explicit speaker it was
   * stamped with, or falls back to a gender-correct British pool pick for a bare spec.
   */
  public int kokoroSpeakerId(VoiceSpec voice) {
    if (voice.player()) {
      return KokoroSpeakerPool.playerSpeaker(voice.gender());
    }
    if (voice.hasExplicitKokoroSpeakerId()) {
      return voice.kokoroSpeakerId();
    }
    return KokoroSpeakerPool.pickNpcSpeakerId(voice.gender(), null, null);
  }

  /** Gender implied by the configured player voice. */
  private NPCGender playerGender() {
    return config.playerVoice().getGender();
  }
}
