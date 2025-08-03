package com.grahambartley;

import com.grahambartley.data.NPCAttributes;
import com.grahambartley.data.NPCDemographicAnalyzer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;

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
   * Complete 16-voice matrix: 8 races × 2 genders each Port assignments: 59125-59140 (16 total
   * voices)
   */
  public enum VoiceProfile {
    // Player voices
    PLAYER_MALE("59125", "Player Male"),
    PLAYER_FEMALE("59126", "Player Female"),

    // Human voices (most common NPCs)
    HUMAN_MALE("59127", "Human Male"),
    HUMAN_FEMALE("59128", "Human Female"),

    // Elf voices (elegant, ethereal)
    ELF_MALE("59129", "Elf Male"),
    ELF_FEMALE("59130", "Elf Female"),

    // Dwarf voices (gruff, sturdy)
    DWARF_MALE("59131", "Dwarf Male"),
    DWARF_FEMALE("59132", "Dwarf Female"),

    // Goblin voices (raspy, crude)
    GOBLIN_MALE("59133", "Goblin Male"),
    GOBLIN_FEMALE("59134", "Goblin Female"),

    // Troll voices (deep, primitive)
    TROLL_MALE("59135", "Troll Male"),
    TROLL_FEMALE("59136", "Troll Female"),

    // Undead voices (hollow, eerie)
    UNDEAD_MALE("59137", "Undead Male"),
    UNDEAD_FEMALE("59138", "Undead Female"),

    // Demon voices (sinister, otherworldly)
    DEMON_MALE("59139", "Demon Male"),
    DEMON_FEMALE("59140", "Demon Female"),

    // Wizard voices (wise, mystical) - special case, can be any base race
    WIZARD_MALE("59129", "Wizard Male"), // Reuses Elf Male for mystical quality
    WIZARD_FEMALE("59130", "Wizard Female"); // Reuses Elf Female for mystical quality

    private final String port;
    private final String displayName;

    VoiceProfile(String port, String displayName) {
      this.port = port;
      this.displayName = displayName;
    }

    public String getPort() {
      return port;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  private final TTSDialogueConfig config;
  private final Client client;
  private final NPCDemographicAnalyzer demographicAnalyzer;

  // Server health checking
  private final Map<String, Boolean> serverHealthCache = new ConcurrentHashMap<>();
  private final Map<String, Long> lastHealthCheck = new ConcurrentHashMap<>();
  private static final long HEALTH_CHECK_INTERVAL_MS = 30000; // 30 seconds
  private static final int CONNECTION_TIMEOUT_MS = 2000; // 2 seconds

  public VoiceManager(TTSDialogueConfig config, Client client) {
    this.config = config;
    this.client = client;
    this.demographicAnalyzer = new NPCDemographicAnalyzer();
    this.demographicAnalyzer.initialize();
  }

  /** Determines the appropriate voice for an NPC based on their race and gender */
  public VoiceProfile getVoiceForNPC(String npcName) {
    if (npcName == null || npcName.isEmpty()) {
      return getDefaultNPCVoice();
    }

    // Get NPC from game world to analyze
    NPC npc = findNPCByName(npcName);
    if (npc == null) {
      log.debug("Could not find NPC '{}' in game world, using default voice", npcName);
      return getDefaultNPCVoice();
    }

    // Use the new demographic analyzer to get comprehensive NPC attributes
    NPCAttributes attributes = demographicAnalyzer.analyzeNPC(npc);

    NPCRace race = NPCRace.UNKNOWN;
    NPCGender gender = NPCGender.UNKNOWN;

    if (attributes != null) {
      // Convert string attributes to enums
      race = convertToNPCRace(attributes.getRace());
      gender = convertToNPCGender(attributes.getGender());

      log.debug(
          "🎭 NPC Detection: '{}' (ID: {}) → Race: {}, Gender: {} → Voice: {} [Source: {}, Confidence: {:.1f}]",
          npcName,
          npc.getId(),
          race,
          gender,
          getVoiceForRaceAndGender(race, gender).getDisplayName(),
          attributes.getSource(),
          attributes.getConfidence());
    } else {
      // Fallback if analysis fails
      race = NPCRace.HUMAN;
      gender = NPCGender.MALE;
      log.debug("Analysis failed for NPC '{}', using default: {} {}", npcName, race, gender);
    }

    return getVoiceForRaceAndGender(race, gender);
  }

  /** Find NPC entity by name in the current game world */
  private NPC findNPCByName(String targetName) {
    if (client == null || client.getNpcs() == null) {
      return null;
    }

    return client.getNpcs().stream()
        .filter(npc -> npc != null && npc.getName() != null)
        .filter(npc -> npc.getName().equals(targetName))
        .findFirst()
        .orElse(null);
  }

  /** Get voice profile for race and gender combination */
  private VoiceProfile getVoiceForRaceAndGender(NPCRace race, NPCGender gender) {
    // Handle wizard as special case - they can be any base race but with mystical voice
    if (race == NPCRace.WIZARD) {
      return gender == NPCGender.FEMALE ? VoiceProfile.WIZARD_FEMALE : VoiceProfile.WIZARD_MALE;
    }

    // Standard race/gender combinations
    switch (race) {
      case HUMAN:
        return gender == NPCGender.FEMALE ? VoiceProfile.HUMAN_FEMALE : VoiceProfile.HUMAN_MALE;
      case ELF:
        return gender == NPCGender.FEMALE ? VoiceProfile.ELF_FEMALE : VoiceProfile.ELF_MALE;
      case DWARF:
        return gender == NPCGender.FEMALE ? VoiceProfile.DWARF_FEMALE : VoiceProfile.DWARF_MALE;
      case GOBLIN:
        return gender == NPCGender.FEMALE ? VoiceProfile.GOBLIN_FEMALE : VoiceProfile.GOBLIN_MALE;
      case TROLL:
        return gender == NPCGender.FEMALE ? VoiceProfile.TROLL_FEMALE : VoiceProfile.TROLL_MALE;
      case UNDEAD:
        return gender == NPCGender.FEMALE ? VoiceProfile.UNDEAD_FEMALE : VoiceProfile.UNDEAD_MALE;
      case DEMON:
        return gender == NPCGender.FEMALE ? VoiceProfile.DEMON_FEMALE : VoiceProfile.DEMON_MALE;
      default:
        // Default to human for unknown races
        return gender == NPCGender.FEMALE ? VoiceProfile.HUMAN_FEMALE : VoiceProfile.HUMAN_MALE;
    }
  }

  /** Gets the default NPC voice (Human Male) */
  private VoiceProfile getDefaultNPCVoice() {
    return VoiceProfile.HUMAN_MALE;
  }

  /** Gets the port for TTS communication with server health checking and fallback */
  public String getPortForSpeaker(String speaker, String npcName) {
    VoiceProfile preferredVoice;

    if ("player".equalsIgnoreCase(speaker)) {
      preferredVoice = config.playerVoice();
    } else {
      preferredVoice = getVoiceForNPC(npcName);
    }

    // Get available port with fallback
    return getAvailablePort(preferredVoice, speaker, npcName);
  }

  /** Get an available TTS server port with health checking and fallback logic */
  private String getAvailablePort(VoiceProfile preferredVoice, String speaker, String npcName) {
    String preferredPort = preferredVoice.getPort();

    // Check if preferred server is healthy
    if (isServerHealthy(preferredPort)) {
      log.debug(
          "Using preferred voice {} (port {}) for {} '{}'",
          preferredVoice.getDisplayName(),
          preferredPort,
          speaker,
          npcName);
      return preferredPort;
    }

    // Log the fallback
    log.warn(
        "Preferred voice server {} (port {}) is not available for {} '{}', trying fallbacks",
        preferredVoice.getDisplayName(),
        preferredPort,
        speaker,
        npcName);

    // Try fallback hierarchy (if enabled)
    if (config.enableFallbacks()) {
      String fallbackPort = findFallbackPort(preferredVoice, speaker);
      if (fallbackPort != null) {
        log.info(
            "Using fallback voice server on port {} for {} '{}'", fallbackPort, speaker, npcName);
        return fallbackPort;
      }
    } else {
      log.debug("Voice fallbacks are disabled in configuration");
    }

    // Last resort: return preferred port anyway (will fail gracefully in TTS method)
    log.error(
        "No healthy TTS servers found! Using preferred port {} anyway for {} '{}'",
        preferredPort,
        speaker,
        npcName);
    return preferredPort;
  }

  /** Find a healthy fallback server based on priority */
  private String findFallbackPort(VoiceProfile preferredVoice, String speaker) {
    // Define fallback priority for different voice types
    VoiceProfile[] fallbackOrder;

    if ("player".equalsIgnoreCase(speaker)) {
      // Player fallbacks: Try other player voice, then human voices
      fallbackOrder =
          new VoiceProfile[] {
            VoiceProfile.PLAYER_MALE,
            VoiceProfile.PLAYER_FEMALE,
            VoiceProfile.HUMAN_MALE,
            VoiceProfile.HUMAN_FEMALE
          };
    } else {
      // NPC fallbacks: Try default human voices, then any available voice
      fallbackOrder =
          new VoiceProfile[] {
            VoiceProfile.HUMAN_MALE, // Default NPC voice
            VoiceProfile.HUMAN_FEMALE, // Default female voice
            VoiceProfile.PLAYER_MALE, // Player voice as fallback
            VoiceProfile.PLAYER_FEMALE, // Player female as fallback
            VoiceProfile.ELF_MALE,
            VoiceProfile.ELF_FEMALE,
            VoiceProfile.DWARF_MALE,
            VoiceProfile.DWARF_FEMALE,
            VoiceProfile.GOBLIN_MALE,
            VoiceProfile.GOBLIN_FEMALE,
            VoiceProfile.TROLL_MALE,
            VoiceProfile.TROLL_FEMALE,
            VoiceProfile.UNDEAD_MALE,
            VoiceProfile.UNDEAD_FEMALE,
            VoiceProfile.DEMON_MALE,
            VoiceProfile.DEMON_FEMALE
          };
    }

    // Try each fallback in order
    for (VoiceProfile fallback : fallbackOrder) {
      if (fallback != preferredVoice && isServerHealthy(fallback.getPort())) {
        return fallback.getPort();
      }
    }

    return null; // No healthy servers found
  }

  /** Check if a TTS server is healthy (responding to requests) */
  private boolean isServerHealthy(String port) {
    // Check cache first
    Long lastCheck = lastHealthCheck.get(port);
    Boolean cachedHealth = serverHealthCache.get(port);

    long currentTime = System.currentTimeMillis();

    // Use cached result if it's recent
    if (lastCheck != null
        && cachedHealth != null
        && (currentTime - lastCheck) < HEALTH_CHECK_INTERVAL_MS) {
      return cachedHealth;
    }

    // Perform health check
    boolean isHealthy = performHealthCheck(port);

    // Update cache
    serverHealthCache.put(port, isHealthy);
    lastHealthCheck.put(port, currentTime);

    return isHealthy;
  }

  /** Perform actual health check by trying to connect to the TTS server */
  private boolean performHealthCheck(String port) {
    try {
      URL url = new URL("http://localhost:" + port + "/");
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "text/plain");
      connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
      connection.setReadTimeout(CONNECTION_TIMEOUT_MS);
      connection.setDoOutput(true);

      // Send a small test text for health check
      String testText = "test";
      try (java.io.OutputStream os = connection.getOutputStream()) {
        os.write(testText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        os.flush();
      }

      int responseCode = connection.getResponseCode();

      // Read and discard the response to complete the request
      try (java.io.InputStream is = connection.getInputStream()) {
        // Just consume the response without storing it
        byte[] buffer = new byte[1024];
        while (is.read(buffer) != -1) {
          // Discard data
        }
      } catch (IOException e) {
        // Ignore errors reading response for health check
      }

      connection.disconnect();

      // Consider 200 OK as healthy
      boolean healthy = (responseCode == 200);

      if (!healthy) {
        log.debug("TTS server health check failed for port {}: HTTP {}", port, responseCode);
      }

      return healthy;

    } catch (IOException e) {
      log.debug("TTS server health check failed for port {}: {}", port, e.getMessage());
      return false;
    }
  }

  /** Clear server health cache (useful for troubleshooting) */
  public void clearHealthCache() {
    serverHealthCache.clear();
    lastHealthCheck.clear();
    log.info("TTS server health cache cleared");
  }

  /** Get current server health status for all voice profiles */
  public Map<VoiceProfile, Boolean> getServerHealthStatus() {
    Map<VoiceProfile, Boolean> status = new HashMap<>();

    for (VoiceProfile voice : VoiceProfile.values()) {
      status.put(voice, isServerHealthy(voice.getPort()));
    }

    return status;
  }

  /** Convert string race attribute to NPCRace enum */
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

      log.debug("Unknown race '{}', defaulting to HUMAN", race);
      return NPCRace.HUMAN;
    }
  }

  /** Convert string gender attribute to NPCGender enum */
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
