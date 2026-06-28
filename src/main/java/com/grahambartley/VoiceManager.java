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
 * Resolves an NPC (or the player) to a backend-neutral {@link VoiceSpec} and, for the local Kokoro
 * backend, to a concrete Kokoro speaker id.
 *
 * <p>Resolution carries race/gender categories so any backend can map the same speaker to its own
 * voice bank. The local Kokoro backend turns a spec into a real speaker id via {@link
 * #kokoroSpeakerId(VoiceSpec)} from the {@link VoiceProfile} matrix: each race/gender category maps
 * to a distinct, clean Kokoro voice so the neural output is the product as-is, with no resampling
 * pitch shift, reverb, or distortion.
 */
@Slf4j
public class VoiceManager {

  public enum NPCRace {
    HUMAN,
    ELF,
    DWARF,
    GOBLIN,
    TROLL,
    UNDEAD,
    DEMON,
    WIZARD,
    UNKNOWN
  }

  public enum NPCGender {
    MALE,
    FEMALE,
    UNKNOWN
  }

  /**
   * Race/gender voice matrix mapped onto distinct speakers from the {@code kokoro-multi-lang-v1_0}
   * bank. Only the English voices are used (American {@code af_/am_} and British {@code bf_/bm_},
   * speaker ids 0-27), picked so each category is clearly differentiable: British accents read as
   * refined (elf, wizard), deeper timbres as gruff (dwarf, troll, demon), lighter timbres as small
   * and crude (goblin), and a whispery voice as eerie (undead). Every entry is a unique speaker id,
   * so categories never collide. See README for the full table.
   */
  public enum VoiceProfile {
    // Player voices
    PLAYER_MALE(16, "am_michael", "Player Male", NPCRace.HUMAN, NPCGender.MALE),
    PLAYER_FEMALE(3, "af_heart", "Player Female", NPCRace.HUMAN, NPCGender.FEMALE),

    // Human voices (most common NPCs) - clear, neutral
    HUMAN_MALE(14, "am_fenrir", "Human Male", NPCRace.HUMAN, NPCGender.MALE),
    HUMAN_FEMALE(2, "af_bella", "Human Female", NPCRace.HUMAN, NPCGender.FEMALE),

    // Elf voices (elegant, refined) - British accent
    ELF_MALE(26, "bm_george", "Elf Male", NPCRace.ELF, NPCGender.MALE),
    ELF_FEMALE(21, "bf_emma", "Elf Female", NPCRace.ELF, NPCGender.FEMALE),

    // Dwarf voices (gruff, sturdy) - deeper British timbres
    DWARF_MALE(27, "bm_lewis", "Dwarf Male", NPCRace.DWARF, NPCGender.MALE),
    DWARF_FEMALE(22, "bf_isabella", "Dwarf Female", NPCRace.DWARF, NPCGender.FEMALE),

    // Goblin voices (small, crude) - lighter, mischievous
    GOBLIN_MALE(18, "am_puck", "Goblin Male", NPCRace.GOBLIN, NPCGender.MALE),
    GOBLIN_FEMALE(10, "af_sky", "Goblin Female", NPCRace.GOBLIN, NPCGender.FEMALE),

    // Troll voices (big, deep, primitive)
    TROLL_MALE(17, "am_onyx", "Troll Male", NPCRace.TROLL, NPCGender.MALE),
    TROLL_FEMALE(9, "af_sarah", "Troll Female", NPCRace.TROLL, NPCGender.FEMALE),

    // Undead voices (hollow, eerie)
    UNDEAD_MALE(12, "am_echo", "Undead Male", NPCRace.UNDEAD, NPCGender.MALE),
    UNDEAD_FEMALE(6, "af_nicole", "Undead Female", NPCRace.UNDEAD, NPCGender.FEMALE),

    // Demon voices (sinister, deep, otherworldly)
    DEMON_MALE(24, "bm_daniel", "Demon Male", NPCRace.DEMON, NPCGender.MALE),
    DEMON_FEMALE(8, "af_river", "Demon Female", NPCRace.DEMON, NPCGender.FEMALE),

    // Wizard voices (wise, mystical) - storyteller British male, distinct female
    WIZARD_MALE(25, "bm_fable", "Wizard Male", NPCRace.WIZARD, NPCGender.MALE),
    WIZARD_FEMALE(0, "af_alloy", "Wizard Female", NPCRace.WIZARD, NPCGender.FEMALE);

    private final int speakerId;
    private final String kokoroVoice;
    private final String displayName;
    private final NPCRace race;
    private final NPCGender gender;

    VoiceProfile(
        int speakerId, String kokoroVoice, String displayName, NPCRace race, NPCGender gender) {
      this.speakerId = speakerId;
      this.kokoroVoice = kokoroVoice;
      this.displayName = displayName;
      this.race = race;
      this.gender = gender;
    }

    /** The race category this profile voices. */
    public NPCRace getRace() {
      return race;
    }

    /** The gender category this profile voices. */
    public NPCGender getGender() {
      return gender;
    }

    /** The Kokoro speaker id fed straight to the in-process synth engine. */
    public int getSpeakerId() {
      return speakerId;
    }

    /** The underlying Kokoro voice name (for docs and logs). */
    public String getKokoroVoice() {
      return kokoroVoice;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  /**
   * Gender-appropriate Kokoro speaker pools for per-NPC voice variety (issue #78). Built once from
   * the {@link VoiceProfile} bank so they can only ever contain gender-correct, in-range English
   * voices: the male pool is every distinct {@code am_}/{@code bm_} NPC speaker, the female pool
   * every distinct {@code af_}/{@code bf_} NPC speaker. Player-only voices are excluded so an NPC
   * never borrows the player's voice. Each pool is sorted ascending for a stable, deterministic
   * index so a given NPC always hashes to the same slot across runs.
   */
  static final int[] MALE_SPEAKER_POOL = buildSpeakerPool(NPCGender.MALE);

  static final int[] FEMALE_SPEAKER_POOL = buildSpeakerPool(NPCGender.FEMALE);

  private static int[] buildSpeakerPool(NPCGender gender) {
    java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
    for (VoiceProfile profile : VoiceProfile.values()) {
      // Skip the player voices: NPC variety must never reach for the player's speaker.
      if (profile == VoiceProfile.PLAYER_MALE || profile == VoiceProfile.PLAYER_FEMALE) {
        continue;
      }
      if (profile.getGender() == gender) {
        ids.add(profile.getSpeakerId());
      }
    }
    int[] pool = new int[ids.size()];
    int i = 0;
    for (int id : ids) {
      pool[i++] = id;
    }
    return pool;
  }

  private final TTSDialogueConfig config;
  private final Client client;
  private final NPCDemographicAnalyzer demographicAnalyzer;
  private final NpcProfileTable profileTable;

  /** Optional runtime wiki fallback for NPCs missing from the bundled table; null when off. */
  private NpcLearningService learningService;

  public VoiceManager(TTSDialogueConfig config, Client client) {
    this.config = config;
    this.client = client;
    this.demographicAnalyzer = new NPCDemographicAnalyzer();
    this.demographicAnalyzer.initialize();
    this.profileTable = new NpcProfileTable();
    this.profileTable.initialize();
  }

  /**
   * Wires in the runtime "learn a new NPC" fallback: the analyzer consults {@code store} for NPCs
   * missing from the bundled table, and an unknown NPC triggers a one-off background wiki lookup
   * via {@code service} that populates {@code store} for subsequent lines.
   */
  public void enableLearning(LearnedNpcStore store, NpcLearningService service) {
    this.demographicAnalyzer.setLearnedStore(store);
    this.learningService = service;
  }

  /**
   * Resolves the {@link CharacterProfile} steering a line's delivery: the player's configured
   * profile for player lines, or the NPC profile built by combining every matching layer (default,
   * race, every keyword category that matches, and any per-NPC override) keyed on the NPC's
   * composition id and display name. Never returns {@code null}. Only the cloud backend renders the
   * profile; the local backend ignores it.
   */
  public CharacterProfile resolveProfile(String speaker, String npcName) {
    if ("player".equalsIgnoreCase(speaker)) {
      CharacterProfile profile =
          profileTable.resolvePlayer(
              config.playerProfileAccent(),
              config.playerProfileStyle(),
              config.playerProfilePace());
      if (config.debugMode()) {
        log.info("[TTS profile] player -> '{}' accent='{}'", profile.name(), profile.accent());
      }
      return profile;
    }

    Integer npcId = null;
    String race = null;
    String ethnicity = null;
    NPC npc = findNPCByName(npcName);
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
   * of the configured player voice; NPCs use their race/gender voice when automatic voices are
   * enabled, otherwise the default NPC voice. Fallback semantics are unchanged.
   */
  public VoiceSpec resolveVoice(String speaker, String npcName) {
    if ("player".equalsIgnoreCase(speaker)) {
      if (config != null && config.debugMode()) {
        log.info(buildPlayerTrace(config.playerVoice()));
      }
      return VoiceSpec.player(playerGender());
    }
    if (!config.enableRaceBasedVoices()) {
      // Automatic voices off: every NPC uses the single default voice, no per-NPC variety.
      VoiceProfile profile = getDefaultNPCVoice();
      return VoiceSpec.npc(profile.getRace(), profile.getGender());
    }
    NpcVoice resolved = resolveNpcVoice(npcName);
    // Stamp the per-NPC Kokoro speaker (issue #78) onto the spec so it rides the wire and the cache
    // key, giving same-race/gender NPCs distinct but stable voices.
    return VoiceSpec.npc(
        resolved.profile.getRace(), resolved.profile.getGender(), resolved.speakerId);
  }

  /**
   * The Kokoro speaker id for a resolved {@link VoiceSpec}, used only by the local Kokoro backend.
   * The player maps to the configured player voice; NPCs map through the {@link VoiceProfile}
   * matrix.
   */
  public int kokoroSpeakerId(VoiceSpec voice) {
    if (voice.player()) {
      // Derive the player speaker id from the spec's gender, not by re-reading config. The spec was
      // stamped from config when the line was resolved, so this keeps the cache key and the audio
      // in
      // agreement even if the player voice is switched between resolution and synthesis.
      return voice.gender() == NPCGender.FEMALE
          ? VoiceProfile.PLAYER_FEMALE.getSpeakerId()
          : VoiceProfile.PLAYER_MALE.getSpeakerId();
    }
    // An NPC that was stamped with a per-NPC speaker (issue #78) keeps it; otherwise fall back to
    // the race/gender baseline so callers that built a bare spec still get a valid voice.
    if (voice.hasExplicitKokoroSpeakerId()) {
      return voice.kokoroSpeakerId();
    }
    return getVoiceForRaceAndGender(voice.race(), voice.gender()).getSpeakerId();
  }

  /**
   * Picks a stable, gender-correct Kokoro speaker for a specific NPC (issue #78). Two NPCs of the
   * same race+gender no longer collapse to the single matrix voice (e.g. every human male = {@code
   * am_fenrir}): they are spread across the gender-appropriate {@link #MALE_SPEAKER_POOL} / {@link
   * #FEMALE_SPEAKER_POOL} by hashing a per-NPC key into the pool index.
   *
   * <p>Keying rule (documented contract): the NPC's composition id is preferred (the same NPC type
   * always hashes the same way, regardless of how its name was presented); when no id is available
   * the normalised name is the fallback key. The pool index is {@code Math.floorMod(hash, size)} so
   * it is non-negative and deterministic. Selection is therefore stable across calls and runs.
   *
   * <p>The baseline race/gender {@code VoiceProfile} is the anchor for gender (the pool is built
   * from gender-correct voices only), so a male NPC can only ever map to a male voice and a female
   * NPC to a female voice. When the pool is empty or the gender is unknown this returns the
   * baseline {@code raceGenderBaseline} speaker id unchanged, preserving pre-#78 behaviour.
   */
  static int pickNpcSpeakerId(VoiceProfile raceGenderBaseline, Integer npcId, String npcName) {
    int[] pool =
        raceGenderBaseline.getGender() == NPCGender.MALE
            ? MALE_SPEAKER_POOL
            : raceGenderBaseline.getGender() == NPCGender.FEMALE ? FEMALE_SPEAKER_POOL : null;
    if (pool == null || pool.length == 0) {
      return raceGenderBaseline.getSpeakerId();
    }
    int hash = npcId != null ? Integer.hashCode(npcId) : normalizeName(npcName).hashCode();
    return pool[Math.floorMod(hash, pool.length)];
  }

  /** Gender implied by the configured player voice profile. */
  private NPCGender playerGender() {
    return config.playerVoice().getGender();
  }

  /**
   * The baseline race/gender {@link VoiceProfile} for an NPC plus the per-NPC Kokoro speaker chosen
   * from the gender pool. The profile fixes race/gender (and hence gender-correctness); the speaker
   * id is the actual voice that gets synthesized.
   */
  private static final class NpcVoice {
    final VoiceProfile profile;
    final int speakerId;

    NpcVoice(VoiceProfile profile, int speakerId) {
      this.profile = profile;
      this.speakerId = speakerId;
    }
  }

  /**
   * Determines the baseline race/gender {@link VoiceProfile} for an NPC. Retained as the public,
   * test-facing accessor; {@link #resolveNpcVoice} layers the per-NPC speaker on top.
   */
  public VoiceProfile getVoiceForNPC(String npcName) {
    return resolveNpcVoice(npcName).profile;
  }

  /**
   * Resolves an NPC to its baseline race/gender {@link VoiceProfile} and a stable, gender-correct
   * per-NPC Kokoro speaker id (issue #78). Emits the debug trace once with the actual chosen
   * speaker so QA can see distinct NPCs getting distinct ids.
   */
  private NpcVoice resolveNpcVoice(String npcName) {
    if (npcName == null || npcName.isEmpty()) {
      return tracedNpcVoice(
          npcName,
          null,
          null,
          NPCGender.UNKNOWN,
          "blank-name",
          getFallbackVoice(NPCGender.UNKNOWN));
    }

    NPC npc = findNPCByName(npcName);
    if (npc == null) {
      return tracedNpcVoice(
          npcName,
          null,
          null,
          NPCGender.UNKNOWN,
          "not-in-world",
          getFallbackVoice(NPCGender.UNKNOWN));
    }

    NPCAttributes attributes = demographicAnalyzer.analyzeNPC(npc);
    if (attributes == null) {
      return tracedNpcVoice(
          npcName,
          npc.getId(),
          null,
          NPCGender.UNKNOWN,
          "analysis-failed",
          getFallbackVoice(NPCGender.UNKNOWN));
    }

    NPCRace race = convertToNPCRace(attributes.getRace());
    NPCGender gender = convertToNPCGender(attributes.getGender());
    String source = "StaticTable".equals(attributes.getSource()) ? "table-hit" : "table-miss";

    // An NPC unknown to the bundled table (and the learned cache) triggers a one-off background
    // wiki
    // lookup when the fallback is enabled, so the next line voices it correctly. No-op otherwise.
    if (race == NPCRace.UNKNOWN && learningService != null) {
      learningService.considerLearning(npc.getId(), npcName);
    }

    // An unrecognised race falls back rather than silently borrowing the human voice, so the
    // fallback toggle stays meaningful.
    VoiceProfile baseline =
        race == NPCRace.UNKNOWN ? getFallbackVoice(gender) : getVoiceForRaceAndGender(race, gender);
    return tracedNpcVoice(npcName, npc.getId(), race, gender, source, baseline);
  }

  /**
   * Picks the per-NPC speaker from {@code baseline}'s gender pool, emits the trace, and packages
   * the result. The composition id keys the choice when present so the same NPC type always voices
   * the same way; otherwise the normalised name is the key.
   */
  private NpcVoice tracedNpcVoice(
      String npcName,
      Integer npcId,
      NPCRace race,
      NPCGender gender,
      String source,
      VoiceProfile baseline) {
    int speakerId = pickNpcSpeakerId(baseline, npcId, npcName);
    if (config != null && config.debugMode()) {
      log.info(buildNpcTrace(npcName, npcId, race, gender, source, baseline, speakerId));
    }
    return new NpcVoice(baseline, speakerId);
  }

  /**
   * Builds the NPC voice-resolution trace string. Factored out (and package-private) so it is
   * unit-testable without a live client or logger.
   *
   * <p>The line exposes the whole resolution path (world hit/id, table hit/miss, resolved
   * race/gender + source, baseline race/gender voice) and, crucially for per-NPC variety (issue
   * #78), the <em>actual chosen</em> Kokoro speaker id/name. The chosen speaker can differ from the
   * baseline profile's own id (two NPCs of the same race/gender are spread across the gender pool),
   * so this logs both: {@code voice=<baseline category>} for the race/gender bucket and {@code
   * speaker=<name>(speakerId=<id>)} for the voice that will actually be synthesized.
   */
  static String buildNpcTrace(
      String npcName,
      Integer npcId,
      NPCRace race,
      NPCGender gender,
      String source,
      VoiceProfile voice,
      int chosenSpeakerId) {
    return String.format(
        "[TTS voice] npc='%s' world=%s race=%s gender=%s source=%s -> voice=%s speaker=%s(speakerId=%d)",
        npcName,
        npcId == null ? "MISS" : "HIT(id=" + npcId + ")",
        race == null ? "UNKNOWN" : race,
        gender,
        source,
        voice.getDisplayName(),
        kokoroVoiceName(chosenSpeakerId),
        chosenSpeakerId);
  }

  /**
   * The {@code kokoro-multi-lang-v1_0} voice name for a speaker id, looked up from the {@link
   * VoiceProfile} bank so the trace reads a friendly name (e.g. {@code am_onyx}) for the chosen
   * per-NPC voice. Unknown ids (outside the mapped bank) fall back to a bare {@code "id=<n>"}
   * label.
   */
  static String kokoroVoiceName(int speakerId) {
    for (VoiceProfile profile : VoiceProfile.values()) {
      if (profile.getSpeakerId() == speakerId) {
        return profile.getKokoroVoice();
      }
    }
    return "id=" + speakerId;
  }

  /** Builds the player voice-resolution trace string. Package-private for unit testing. */
  static String buildPlayerTrace(VoiceProfile voice) {
    return String.format(
        "[TTS voice] player -> voice=%s (%s, speakerId=%d)",
        voice.getDisplayName(), voice.getKokoroVoice(), voice.getSpeakerId());
  }

  /**
   * Find NPC entity by name in the current game world. Matching is tolerant of presentation
   * differences between the dialogue name widget (which can carry {@code <col=...>} markup,
   * non-breaking spaces, and casing) and the raw composition name: both sides are stripped of any
   * {@code <...>} tags, have non-breaking spaces normalised, are trimmed, and compared
   * case-insensitively. This stops cosmetic markup from forcing a false miss and the fallback
   * voice.
   */
  private NPC findNPCByName(String targetName) {
    if (client == null || client.getNpcs() == null) {
      return null;
    }

    String wanted = normalizeName(targetName);
    if (wanted.isEmpty()) {
      return null;
    }

    return client.getNpcs().stream()
        .filter(npc -> npc != null && npc.getName() != null)
        .filter(npc -> normalizeName(npc.getName()).equalsIgnoreCase(wanted))
        .findFirst()
        .orElse(null);
  }

  /**
   * Normalises an NPC name for tolerant matching: strips any {@code <...>} tags, converts
   * non-breaking spaces to regular spaces, and trims. Case is handled by the caller's comparison.
   * Package-private for unit testing.
   */
  static String normalizeName(String name) {
    if (name == null) {
      return "";
    }
    return name.replaceAll("<[^>]*>", "").replace(' ', ' ').trim();
  }

  /** Get voice profile for a known race and gender combination. */
  private VoiceProfile getVoiceForRaceAndGender(NPCRace race, NPCGender gender) {
    boolean female = gender == NPCGender.FEMALE;
    switch (race) {
      case ELF:
        return female ? VoiceProfile.ELF_FEMALE : VoiceProfile.ELF_MALE;
      case DWARF:
        return female ? VoiceProfile.DWARF_FEMALE : VoiceProfile.DWARF_MALE;
      case GOBLIN:
        return female ? VoiceProfile.GOBLIN_FEMALE : VoiceProfile.GOBLIN_MALE;
      case TROLL:
        return female ? VoiceProfile.TROLL_FEMALE : VoiceProfile.TROLL_MALE;
      case UNDEAD:
        return female ? VoiceProfile.UNDEAD_FEMALE : VoiceProfile.UNDEAD_MALE;
      case DEMON:
        return female ? VoiceProfile.DEMON_FEMALE : VoiceProfile.DEMON_MALE;
      case WIZARD:
        return female ? VoiceProfile.WIZARD_FEMALE : VoiceProfile.WIZARD_MALE;
      case HUMAN:
      default:
        return female ? VoiceProfile.HUMAN_FEMALE : VoiceProfile.HUMAN_MALE;
    }
  }

  /**
   * Voice used when race detection fails or the race is unrecognised. With fallbacks enabled the
   * caller still gets a gender-appropriate human voice; with fallbacks disabled everything
   * collapses to a single default voice.
   */
  private VoiceProfile getFallbackVoice(NPCGender gender) {
    if (!config.enableFallbacks()) {
      return getDefaultNPCVoice();
    }
    return gender == NPCGender.FEMALE ? VoiceProfile.HUMAN_FEMALE : VoiceProfile.HUMAN_MALE;
  }

  /** The single default NPC voice (Human Male). */
  private VoiceProfile getDefaultNPCVoice() {
    return VoiceProfile.HUMAN_MALE;
  }

  /** Convert string race attribute to NPCRace enum. */
  private NPCRace convertToNPCRace(String race) {
    if (race == null || race.isEmpty()) {
      return NPCRace.UNKNOWN;
    }

    try {
      return NPCRace.valueOf(race.toUpperCase());
    } catch (IllegalArgumentException e) {
      // Handle mappings for races not directly in our enum
      String raceLower = race.toLowerCase();

      if (raceLower.contains("human")
          || raceLower.contains("man")
          || raceLower.contains("person")) {
        return NPCRace.HUMAN;
      } else if (raceLower.contains("elf") || raceLower.contains("elven")) {
        return NPCRace.ELF;
      } else if (raceLower.contains("dwarf") || raceLower.contains("dwarven")) {
        return NPCRace.DWARF;
      } else if (raceLower.contains("goblin") || raceLower.contains("gnome")) {
        return NPCRace.GOBLIN;
      } else if (raceLower.contains("troll") || raceLower.contains("giant")) {
        return NPCRace.TROLL;
      } else if (raceLower.contains("undead")
          || raceLower.contains("skeleton")
          || raceLower.contains("zombie")
          || raceLower.contains("ghost")) {
        return NPCRace.UNDEAD;
      } else if (raceLower.contains("demon")
          || raceLower.contains("dragon")
          || raceLower.contains("devil")) {
        return NPCRace.DEMON;
      } else if (raceLower.contains("wizard") || raceLower.contains("mage")) {
        return NPCRace.WIZARD;
      }

      log.debug("Unknown race '{}', using fallback voice", race);
      return NPCRace.UNKNOWN;
    }
  }

  /** Convert string gender attribute to NPCGender enum. */
  private NPCGender convertToNPCGender(String gender) {
    if (gender == null || gender.isEmpty()) {
      return NPCGender.UNKNOWN;
    }

    try {
      return NPCGender.valueOf(gender.toUpperCase());
    } catch (IllegalArgumentException e) {
      // Handle various gender representations
      String genderLower = gender.toLowerCase();

      if (genderLower.contains("female")
          || genderLower.contains("woman")
          || genderLower.contains("girl")
          || genderLower.contains("lady")) {
        return NPCGender.FEMALE;
      } else if (genderLower.contains("male")
          || genderLower.contains("man")
          || genderLower.contains("boy")
          || genderLower.contains("lord")) {
        return NPCGender.MALE;
      }

      log.debug("Unknown gender '{}', defaulting to MALE", gender);
      return NPCGender.MALE;
    }
  }
}
