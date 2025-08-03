package com.grahambartley.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;

/**
 * Advanced NPC demographic analyzer that combines multiple data sources: 1. Community-maintained
 * mappings (highest priority) 2. External API data (OSRS Wiki first, then OSRSBox static data) 3.
 * Name-based inference 4. Model-based analysis 5. Contextual analysis
 *
 * <p>Uses a hybrid approach as recommended in the research document.
 */
@Slf4j
public class NPCDemographicAnalyzer {

  private final ExternalNPCDataClient externalClient;

  // Local caches for performance
  private final Map<Integer, NPCAttributes> communityMappings = new ConcurrentHashMap<>();
  private final Map<Integer, NPCAttributes> analysisCache = new ConcurrentHashMap<>();
  private final Map<String, NPCAttributes> nameCache = new ConcurrentHashMap<>();

  // Pattern mappings from community data
  private Map<String, String> raceKeywords = new ConcurrentHashMap<>();
  private JsonObject genderKeywords;

  // Performance metrics
  private long totalRequests = 0;
  private long cacheHits = 0;
  private long externalApiCalls = 0;

  public NPCDemographicAnalyzer() {
    this.externalClient = new ExternalNPCDataClient();
  }

  public void initialize() {
    loadCommunityMappings();
    log.info(
        "NPC Demographic Analyzer initialized with {} community mappings",
        communityMappings.size());
  }

  /** Main analysis method - tries all data sources in priority order */
  public NPCAttributes analyzeNPC(NPC npc) {
    if (npc == null) return null;

    totalRequests++;

    NPCComposition composition = npc.getComposition();
    if (composition == null) return null;

    int npcId = composition.getId();
    String npcName = composition.getName();

    // 1. Check analysis cache first
    NPCAttributes cached = analysisCache.get(npcId);
    if (cached != null) {
      cacheHits++;
      log.debug(
          "Cache hit for NPC {} ({}): {} {}", npcId, npcName, cached.getRace(), cached.getGender());
      return cached;
    }

    // 2. Check community mappings (highest confidence)
    NPCAttributes communityData = communityMappings.get(npcId);
    if (communityData != null) {
      log.debug(
          "Community mapping for {} ({}): {} {}",
          npcId,
          npcName,
          communityData.getRace(),
          communityData.getGender());
      analysisCache.put(npcId, communityData);
      return communityData;
    }

    // 3. Try external API lookup (Wiki first, then OSRSBox static data) (async with timeout)
    NPCAttributes externalData = tryExternalLookup(npcId, npcName);
    if (externalData != null && (externalData.hasValidRace() || externalData.hasValidGender())) {
      log.debug(
          "External API data for {} ({}): {} {} from {}",
          npcId,
          npcName,
          externalData.getRace(),
          externalData.getGender(),
          externalData.getSource());
      analysisCache.put(npcId, externalData);
      return externalData;
    }

    // 4. Fallback to local inference (name + model analysis)
    NPCAttributes inferredData = performLocalInference(npc, npcId, npcName);
    if (inferredData != null) {
      log.debug(
          "Local inference for {} ({}): {} {}",
          npcId,
          npcName,
          inferredData.getRace(),
          inferredData.getGender());
      analysisCache.put(npcId, inferredData);
      return inferredData;
    }

    // 5. Default fallback
    NPCAttributes defaultData = new NPCAttributes("Human", "Male", "Default", 0.1);
    defaultData.setNpcId(npcId);
    defaultData.setName(npcName);
    defaultData.setNotes("No demographic data found - using default");

    analysisCache.put(npcId, defaultData);
    return defaultData;
  }

  /** Try external API lookup with timeout to avoid blocking gameplay */
  private NPCAttributes tryExternalLookup(int npcId, String npcName) {
    try {
      externalApiCalls++;
      CompletableFuture<NPCAttributes> future = externalClient.getNPCAttributes(npcId, npcName);

      // Short timeout to avoid impacting gameplay performance
      return future.get(2, TimeUnit.SECONDS);

    } catch (TimeoutException e) {
      log.debug("External API lookup timed out for NPC {} ({})", npcId, npcName);
      return null;
    } catch (InterruptedException | ExecutionException e) {
      log.debug("External API lookup failed for NPC {} ({}): {}", npcId, npcName, e.getMessage());
      return null;
    }
  }

  /** Perform local inference using multiple techniques */
  private NPCAttributes performLocalInference(NPC npc, int npcId, String npcName) {
    if (npcName == null || npcName.isEmpty()) return null;

    // Check name cache first
    NPCAttributes nameCache = this.nameCache.get(npcName);
    if (nameCache != null) {
      return nameCache;
    }

    String inferredRace = null;
    String inferredGender = null;
    double confidence = 0.3; // Default low confidence for inference
    String source = "Inference";
    StringBuilder notes = new StringBuilder();

    // Name-based analysis
    inferredRace = inferRaceFromName(npcName);
    inferredGender = inferGenderFromName(npcName);

    if (inferredRace != null) {
      notes.append("Race from name patterns; ");
      confidence = Math.max(confidence, 0.5);
    }

    if (inferredGender != null) {
      notes.append("Gender from name patterns; ");
      confidence = Math.max(confidence, 0.5);
    }

    // Model-based analysis (if available)
    NPCComposition composition = npc.getComposition();
    if (composition != null && composition.getModels() != null) {
      String modelRace = inferRaceFromModels(composition.getModels());
      if (modelRace != null && inferredRace == null) {
        inferredRace = modelRace;
        notes.append("Race from model analysis; ");
        confidence = Math.max(confidence, 0.4);
      }
    }

    // Contextual analysis (size, combat level, etc.)
    if (composition != null) {
      String contextualInfo = analyzeContextualClues(composition);
      if (!contextualInfo.isEmpty()) {
        notes.append(contextualInfo);
      }
    }

    if (inferredRace != null || inferredGender != null) {
      NPCAttributes attributes =
          new NPCAttributes(
              inferredRace != null ? inferredRace : "Unknown",
              inferredGender != null ? inferredGender : "Unknown",
              source,
              confidence);

      attributes.setNpcId(npcId);
      attributes.setName(npcName);
      attributes.setNotes(notes.toString());

      // Cache name-based results for performance
      this.nameCache.put(npcName, attributes);

      return attributes;
    }

    return null;
  }

  /** Infer race from NPC name using community patterns */
  private String inferRaceFromName(String name) {
    if (name == null) return null;

    String nameLower = name.toLowerCase();

    // Check direct keyword matches
    for (Map.Entry<String, String> entry : raceKeywords.entrySet()) {
      if (nameLower.contains(entry.getKey())) {
        return entry.getValue();
      }
    }

    // Additional heuristics
    if (nameLower.matches(".*(thor|odin|erik|bjorn|olaf|ragnar).*")) return "Human";
    if (nameLower.matches(".*(gimli|thorin|balin|dwalin|fili|kili|durin).*")) return "Dwarf";
    if (nameLower.matches(".*(legolas|elrond|arwen|galadriel|thranduil).*")) return "Elf";

    return null;
  }

  /** Infer gender from NPC name using community patterns */
  private String inferGenderFromName(String name) {
    if (name == null || genderKeywords == null) return null;

    String nameLower = name.toLowerCase();

    // Direct gender indicators
    if (nameLower.equals("man") || nameLower.equals("boy")) return "Male";
    if (nameLower.equals("woman") || nameLower.equals("girl")) return "Female";

    // Check title patterns
    if (genderKeywords.has("male_titles")) {
      for (var title : genderKeywords.getAsJsonArray("male_titles")) {
        if (nameLower.contains(title.getAsString())) return "Male";
      }
    }

    if (genderKeywords.has("female_titles")) {
      for (var title : genderKeywords.getAsJsonArray("female_titles")) {
        if (nameLower.contains(title.getAsString())) return "Female";
      }
    }

    // Check name endings
    if (genderKeywords.has("female_name_endings")) {
      for (var ending : genderKeywords.getAsJsonArray("female_name_endings")) {
        if (nameLower.endsWith(ending.getAsString())) return "Female";
      }
    }

    if (genderKeywords.has("male_name_endings")) {
      for (var ending : genderKeywords.getAsJsonArray("male_name_endings")) {
        if (nameLower.endsWith(ending.getAsString())) return "Male";
      }
    }

    return null;
  }

  /** Infer race from NPC model IDs (requires research into model patterns) */
  private String inferRaceFromModels(int[] modelIds) {
    if (modelIds == null || modelIds.length == 0) return null;

    // This is a placeholder - would need actual research into OSRS model ID patterns
    // Different races likely use different model ID ranges

    for (int modelId : modelIds) {
      // Example patterns (these would need to be researched and verified)
      if (isInRange(modelId, 200, 220)) return "Dwarf";
      if (isInRange(modelId, 300, 320)) return "Elf";
      if (isInRange(modelId, 100, 120)) return "Goblin";
      if (isInRange(modelId, 400, 420)) return "Troll";
    }

    return null;
  }

  private boolean isInRange(int value, int min, int max) {
    return value >= min && value <= max;
  }

  /** Analyze contextual clues from NPC composition */
  private String analyzeContextualClues(NPCComposition composition) {
    StringBuilder notes = new StringBuilder();

    // Size analysis
    int size = composition.getSize();
    if (size > 2) {
      notes.append("Large size suggests giant/troll; ");
    } else if (size < 1) {
      notes.append("Small size suggests goblin/gnome; ");
    }

    // Combat level patterns
    int combatLevel = composition.getCombatLevel();
    if (combatLevel > 100) {
      notes.append("High combat level; ");
    }

    // Scale analysis
    int widthScale = composition.getWidthScale();
    int heightScale = composition.getHeightScale();

    if (widthScale < 100 || heightScale < 100) {
      notes.append("Smaller scale suggests non-human race; ");
    }

    return notes.toString();
  }

  /** Load community-maintained NPC mappings from resources */
  private void loadCommunityMappings() {
    try {
      InputStream stream = getClass().getResourceAsStream("/npc-mappings.json");
      if (stream == null) {
        log.warn("Community NPC mappings file not found - using inference only");
        return;
      }

      JsonObject root =
          new JsonParser()
              .parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject();

      // Load NPC mappings
      if (root.has("npcs")) {
        JsonObject npcs = root.getAsJsonObject("npcs");
        for (String npcIdStr : npcs.keySet()) {
          try {
            int npcId = Integer.parseInt(npcIdStr);
            JsonObject npcData = npcs.getAsJsonObject(npcIdStr);

            NPCAttributes attributes = new NPCAttributes();
            attributes.setNpcId(npcId);
            attributes.setName(npcData.get("name").getAsString());
            attributes.setRace(npcData.get("race").getAsString());
            attributes.setGender(npcData.get("gender").getAsString());
            attributes.setConfidence(npcData.get("confidence").getAsDouble());
            attributes.setSource(npcData.get("source").getAsString());

            if (npcData.has("notes")) {
              attributes.setNotes(npcData.get("notes").getAsString());
            }

            communityMappings.put(npcId, attributes);

          } catch (Exception e) {
            log.warn("Failed to parse NPC mapping for ID {}: {}", npcIdStr, e.getMessage());
          }
        }
      }

      // Load pattern mappings
      if (root.has("patterns")) {
        JsonObject patterns = root.getAsJsonObject("patterns");

        if (patterns.has("race_keywords")) {
          JsonObject raceKeywordObj = patterns.getAsJsonObject("race_keywords");
          for (String keyword : raceKeywordObj.keySet()) {
            raceKeywords.put(keyword, raceKeywordObj.get(keyword).getAsString());
          }
        }

        if (patterns.has("gender_keywords")) {
          genderKeywords = patterns.getAsJsonObject("gender_keywords");
        }
      }

      log.info(
          "Loaded {} community NPC mappings and {} race keywords",
          communityMappings.size(),
          raceKeywords.size());

    } catch (Exception e) {
      log.error("Failed to load community NPC mappings: {}", e.getMessage());
    }
  }

  /** Get performance statistics */
  public String getPerformanceStats() {
    double cacheHitRate = totalRequests > 0 ? (double) cacheHits / totalRequests * 100 : 0;

    return String.format(
        "NPC Analysis Stats: %d total requests, %.1f%% cache hit rate, %d external API calls, %d cached mappings",
        totalRequests, cacheHitRate, externalApiCalls, analysisCache.size());
  }

  /** Clear all caches */
  public void clearCaches() {
    analysisCache.clear();
    nameCache.clear();
    externalClient.clearCache();

    // Reset performance counters
    totalRequests = 0;
    cacheHits = 0;
    externalApiCalls = 0;

    log.info("All NPC demographic caches cleared");
  }

  /** Get cache sizes for monitoring */
  public Map<String, Integer> getCacheSizes() {
    return Map.of(
        "community", communityMappings.size(),
        "analysis", analysisCache.size(),
        "name", nameCache.size(),
        "external", externalClient.getCacheSize());
  }
}
