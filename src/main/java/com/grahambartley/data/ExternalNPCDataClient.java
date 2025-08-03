package com.grahambartley.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Client for fetching NPC demographic data from external sources. Priority order: 1. OSRS Wiki API,
 * 2. Cached OSRSBox static data Implements caching and rate limiting to be respectful to external
 * APIs.
 */
@Slf4j
public class ExternalNPCDataClient {

  private static final String OSRSBOX_MONSTERS_JSON =
      "https://www.osrsbox.com/osrsbox-db/monsters-complete.json";
  private static final String OSRS_WIKI_API_BASE = "https://oldschool.runescape.wiki/api.php";

  private final HttpClient httpClient;
  private final Gson gson;
  private final Map<Integer, NPCAttributes> cache = new ConcurrentHashMap<>();
  private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();
  private final Map<Integer, JsonObject> osrsBoxData = new ConcurrentHashMap<>();
  private volatile boolean osrsBoxDataLoaded = false;
  private static final long RATE_LIMIT_MS = 1000; // 1 second between requests per source

  public ExternalNPCDataClient() {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.gson = new Gson();
    loadOsrsBoxData();
  }

  /** Load OSRSBox monster data from static JSON */
  private void loadOsrsBoxData() {
    log.info("Loading OSRSBox static data...");
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(OSRSBOX_MONSTERS_JSON))
            .header("User-Agent", "RuneLite-TTS-Plugin/1.0")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30)) // Increased timeout for large file
            .GET()
            .build();

    httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(
            response -> {
              if (response.statusCode() != 200) {
                log.warn("OSRSBox static data request returned status {}", response.statusCode());
                return null;
              }
              String body = response.body();
              log.info("OSRSBox static data downloaded, size: {} bytes", body.length());
              return body;
            })
        .thenAccept(
            body -> {
              if (body == null) {
                log.warn("OSRSBox static data body is null, skipping parsing");
                return;
              }

              try {
                // Parse JSON with better error handling
                JsonObject root = new JsonParser().parse(body).getAsJsonObject();
                int loaded = 0;
                int skipped = 0;

                for (String key : root.keySet()) {
                  try {
                    int npcId = Integer.parseInt(key);
                    JsonObject npcData = root.getAsJsonObject(key);
                    if (npcData != null) {
                      osrsBoxData.put(npcId, npcData);
                      loaded++;
                    } else {
                      skipped++;
                    }
                  } catch (NumberFormatException e) {
                    log.debug("Skipping non-numeric key: {}", key);
                    skipped++;
                  } catch (Exception e) {
                    log.debug("Error processing NPC {}: {}", key, e.getMessage());
                    skipped++;
                  }
                }

                osrsBoxDataLoaded = true;
                log.info(
                    "OSRSBox static data loaded: {} entries loaded, {} skipped", loaded, skipped);

              } catch (Exception e) {
                log.error(
                    "Failed to parse OSRSBox static data: {} - {}",
                    e.getClass().getSimpleName(),
                    e.getMessage());
                log.info(
                    "OSRSBox static data will be unavailable, continuing with other data sources");
              }
            })
        .exceptionally(
            e -> {
              log.error(
                  "Failed to load OSRSBox static data: {} - {}",
                  e.getClass().getSimpleName(),
                  e.getMessage());
              log.info(
                  "OSRSBox static data will be unavailable, continuing with other data sources");
              return null;
            });
  }

  /** Get NPC attributes by ID, trying multiple external sources with caching */
  public CompletableFuture<NPCAttributes> getNPCAttributes(int npcId, String npcName) {
    // Check cache first
    NPCAttributes cached = cache.get(npcId);
    if (cached != null) {
      log.debug(
          "Cache hit for NPC {} ({}): {} {}", npcId, npcName, cached.getRace(), cached.getGender());
      return CompletableFuture.completedFuture(cached);
    }

    // Try external sources asynchronously, prioritize Wiki API
    return tryWikiLookup(npcId, npcName)
        .thenCompose(
            result -> {
              if (result != null && (result.hasValidRace() || result.hasValidGender())) {
                cache.put(npcId, result);
                return CompletableFuture.completedFuture(result);
              }

              // Fallback to OSRSBox static data
              return tryOSRSBoxStaticLookup(npcId);
            })
        .thenApply(
            result -> {
              if (result != null) {
                cache.put(npcId, result);
              }
              return result;
            })
        .exceptionally(
            throwable -> {
              log.debug(
                  "External NPC lookup failed for {} ({}): {}",
                  npcId,
                  npcName,
                  throwable.getMessage());
              return null;
            });
  }

  /** Try to get NPC data from OSRSBox static JSON */
  private CompletableFuture<NPCAttributes> tryOSRSBoxStaticLookup(int npcId) {
    if (!osrsBoxDataLoaded) {
      log.debug("OSRSBox static data not yet loaded, cannot lookup NPC {}", npcId);
      return CompletableFuture.completedFuture(null);
    }

    JsonObject json = osrsBoxData.get(npcId);
    if (json == null) {
      log.debug("No OSRSBox static data found for NPC {}", npcId);
      return CompletableFuture.completedFuture(null);
    }

    String name = json.has("name") ? json.get("name").getAsString() : "Unknown";
    String examine = json.has("examine") ? json.get("examine").getAsString() : "";

    NPCAttributes attributes = inferFromOSRSBoxData(name, examine);
    if (attributes == null) {
      attributes = new NPCAttributes();
      attributes.setRace("Unknown");
      attributes.setGender("Unknown");
      attributes.setConfidence(0.1);
    }

    attributes.setNpcId(npcId);
    attributes.setName(name);
    attributes.setSource("OSRSBox-Static");

    log.debug(
        "OSRSBox static lookup for NPC {} ({}): {} {}",
        npcId,
        name,
        attributes.getRace(),
        attributes.getGender());
    return CompletableFuture.completedFuture(attributes);
  }

  /** Try to get NPC data from OSRS Wiki API */
  private CompletableFuture<NPCAttributes> tryWikiLookup(int npcId, String npcName) {
    if (!respectRateLimit("wiki") || npcName == null || npcName.isEmpty()) {
      log.debug(
          "Rate limit hit or invalid name for Wiki API, skipping lookup for NPC {} ({})",
          npcId,
          npcName);
      return CompletableFuture.completedFuture(null);
    }

    // Query the wiki for the NPC page
    String encodedName = URLEncoder.encode(npcName, StandardCharsets.UTF_8);
    String url =
        OSRS_WIKI_API_BASE
            + "?action=query&format=json&prop=revisions&rvprop=content&titles="
            + encodedName;

    log.debug("Making Wiki API request for NPC {} ({}): {}", npcId, npcName, url);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "RuneLite-TTS-Plugin/1.0 (contact: your-email@example.com)")
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

    return httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .whenComplete(
            (response, throwable) -> {
              if (throwable != null) {
                log.debug(
                    "Wiki API request failed for NPC {} ({}): {}",
                    npcId,
                    npcName,
                    throwable.getMessage());
              } else {
                log.debug(
                    "Wiki API response for NPC {} ({}): status {}, body length {}",
                    npcId,
                    npcName,
                    response.statusCode(),
                    response.body().length());
              }
            })
        .thenApply(
            response -> {
              if (response.statusCode() == 200) {
                log.debug("Processing Wiki response for NPC {} ({})", npcId, npcName);
                return parseWikiResponse(response.body(), npcId, npcName);
              } else {
                log.debug(
                    "Wiki API returned status {} for NPC {} ({})",
                    response.statusCode(),
                    npcId,
                    npcName);
                return null;
              }
            })
        .exceptionally(
            throwable -> {
              log.debug(
                  "Wiki API call exception for NPC {} ({}): {}",
                  npcId,
                  npcName,
                  throwable.getMessage());
              return null;
            });
  }

  /** Parse OSRSBox API response to extract demographic information */
  private NPCAttributes parseOSRSBoxResponse(String responseBody, int npcId, String npcName) {
    try {
      JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();

      // OSRSBox doesn't have explicit race/gender fields, but we can infer from other data
      String name = json.has("name") ? json.get("name").getAsString() : npcName;
      String examine = json.has("examine") ? json.get("examine").getAsString() : "";

      // Try to infer demographics from examine text and name
      NPCAttributes attributes = inferFromOSRSBoxData(name, examine);
      if (attributes != null) {
        attributes.setNpcId(npcId);
        attributes.setName(name);
        attributes.setSource("OSRSBox");
        attributes.setConfidence(0.6); // Medium confidence for inferred data
        log.debug(
            "OSRSBox inference for {} ({}): {} {}",
            npcId,
            name,
            attributes.getRace(),
            attributes.getGender());
      }

      return attributes;

    } catch (Exception e) {
      log.debug("Failed to parse OSRSBox response for NPC {}: {}", npcId, e.getMessage());
      return null;
    }
  }

  /** Parse Wiki API response to extract demographic information from infoboxes */
  private NPCAttributes parseWikiResponse(String responseBody, int npcId, String npcName) {
    try {
      JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();

      // Navigate to the page content
      JsonObject query = json.getAsJsonObject("query");
      if (query == null) return null;

      JsonObject pages = query.getAsJsonObject("pages");
      if (pages == null) return null;

      // Get the first (and usually only) page
      for (String pageId : pages.keySet()) {
        JsonObject page = pages.getAsJsonObject(pageId);
        if (page.has("revisions")) {
          // The content is in the "*" field, not "content"
          String content =
              page.getAsJsonArray("revisions").get(0).getAsJsonObject().get("*").getAsString();

          return parseWikiPageContent(content, npcId, npcName);
        }
      }

      return null;

    } catch (Exception e) {
      log.debug("Failed to parse Wiki response for NPC {}: {}", npcName, e.getMessage());
      return null;
    }
  }

  /** Extract demographic information from Wiki page content (looking for infoboxes) */
  private NPCAttributes parseWikiPageContent(String content, int npcId, String npcName) {
    // Look for common infobox patterns that might contain demographic info
    String race = extractInfoboxValue(content, "race");
    if (race == null) race = extractInfoboxValue(content, "species");

    String gender = extractInfoboxValue(content, "gender");
    if (gender == null) gender = extractInfoboxValue(content, "sex");

    // Also look for pronouns in the text
    if (gender == null) {
      gender = inferGenderFromPronouns(content);
    }

    if (race != null || gender != null) {
      NPCAttributes attributes = new NPCAttributes();
      attributes.setRace(normalizeRace(race));
      attributes.setGender(normalizeGender(gender));
      attributes.setNpcId(npcId);
      attributes.setName(npcName);
      attributes.setSource("Wiki");
      attributes.setConfidence(0.8); // High confidence for wiki data

      log.debug(
          "Wiki extraction for {} ({}): {} {}",
          npcId,
          npcName,
          attributes.getRace(),
          attributes.getGender());
      return attributes;
    }

    return null;
  }

  /** Extract value from wiki infobox syntax */
  private String extractInfoboxValue(String content, String key) {
    // Look for patterns like |race = Human or |gender = Female
    // Also handle wiki links like |race = [[Human]] or |race = [[Elf|elven]]
    String pattern = "\\|\\s*" + key + "\\s*=\\s*([^\\n\\|]+)";
    java.util.regex.Pattern regex =
        java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
    java.util.regex.Matcher matcher = regex.matcher(content);

    if (matcher.find()) {
      String value = matcher.group(1).trim();

      // Clean up wiki markup
      // Remove [[link]] markup but keep the text
      value = value.replaceAll("\\[\\[([^\\]\\|]+)(?:\\|[^\\]]+)?\\]\\]", "$1");
      // Remove any remaining markup
      value = value.replaceAll("[{}\\[\\]]", "").trim();

      return value.isEmpty() ? null : value;
    }

    return null;
  }

  /** Infer gender from pronoun usage in text */
  private String inferGenderFromPronouns(String content) {
    String lowerContent = content.toLowerCase();

    // Count gendered pronouns
    int heCount =
        countOccurrences(lowerContent, "\\bhe\\b")
            + countOccurrences(lowerContent, "\\bhim\\b")
            + countOccurrences(lowerContent, "\\bhis\\b");
    int sheCount =
        countOccurrences(lowerContent, "\\bshe\\b")
            + countOccurrences(lowerContent, "\\bher\\b")
            + countOccurrences(lowerContent, "\\bhers\\b");

    // Need significant difference to make a determination
    if (heCount > sheCount + 2) return "Male";
    if (sheCount > heCount + 2) return "Female";

    return null;
  }

  private int countOccurrences(String text, String pattern) {
    java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
    java.util.regex.Matcher matcher = regex.matcher(text);
    int count = 0;
    while (matcher.find()) count++;
    return count;
  }

  /** Infer demographics from OSRSBox data (name and examine text) */
  private NPCAttributes inferFromOSRSBoxData(String name, String examine) {
    if (name == null) return null;

    String inferredRace = inferRaceFromName(name);
    String inferredGender = inferGenderFromName(name);

    // Also check examine text for additional clues
    if (examine != null) {
      String examineGender = inferGenderFromPronouns(examine);
      if (examineGender != null) {
        inferredGender = examineGender;
      }
    }

    if (inferredRace != null || inferredGender != null) {
      return new NPCAttributes(inferredRace, inferredGender);
    }

    return null;
  }

  /** Infer race from NPC name using common patterns */
  private String inferRaceFromName(String name) {
    if (name == null) return null;

    String nameLower = name.toLowerCase();

    // Direct race indicators
    if (nameLower.contains("elf") || nameLower.contains("elven")) return "Elf";
    if (nameLower.contains("dwarf") || nameLower.contains("dwarven")) return "Dwarf";
    if (nameLower.contains("goblin")) return "Goblin";
    if (nameLower.contains("troll")) return "Troll";
    if (nameLower.contains("demon") || nameLower.contains("devil")) return "Demon";
    if (nameLower.contains("zombie")
        || nameLower.contains("skeleton")
        || nameLower.contains("ghost")) return "Undead";

    // Cultural/regional indicators
    if (nameLower.matches(".*(thor|odin|erik|bjorn|olaf|ragnar).*"))
      return "Human"; // Norse-inspired
    if (nameLower.matches(".*(gimli|thorin|balin|dwalin|fili|kili).*"))
      return "Dwarf"; // Dwarf-like names

    return null;
  }

  /** Infer gender from NPC name using common patterns */
  private String inferGenderFromName(String name) {
    if (name == null) return null;

    String nameLower = name.toLowerCase();

    // Direct gender indicators
    if (nameLower.equals("man") || nameLower.equals("boy")) return "Male";
    if (nameLower.equals("woman") || nameLower.equals("girl")) return "Female";

    // Titles
    if (nameLower.contains("king")
        || nameLower.contains("lord")
        || nameLower.contains("sir")
        || nameLower.contains("prince")
        || nameLower.contains("duke")) return "Male";
    if (nameLower.contains("queen")
        || nameLower.contains("lady")
        || nameLower.contains("princess")
        || nameLower.contains("duchess")) return "Female";

    // Common name endings (not foolproof but helpful)
    if (nameLower.endsWith("a") || nameLower.endsWith("ella") || nameLower.endsWith("ina")) {
      return "Female";
    }

    return null;
  }

  /** Normalize race strings to standard values */
  private String normalizeRace(String race) {
    if (race == null) return null;

    String normalized = race.toLowerCase().trim();

    // Map variations to standard race names
    Map<String, String> raceMap = new HashMap<>();
    raceMap.put("human", "Human");
    raceMap.put("elf", "Elf");
    raceMap.put("elven", "Elf");
    raceMap.put("dwarf", "Dwarf");
    raceMap.put("dwarven", "Dwarf");
    raceMap.put("goblin", "Goblin");
    raceMap.put("troll", "Troll");
    raceMap.put("demon", "Demon");
    raceMap.put("undead", "Undead");
    raceMap.put("zombie", "Undead");
    raceMap.put("skeleton", "Undead");
    raceMap.put("ghost", "Undead");

    return raceMap.getOrDefault(normalized, race);
  }

  /** Normalize gender strings to standard values */
  private String normalizeGender(String gender) {
    if (gender == null) return null;

    String normalized = gender.toLowerCase().trim();

    if (normalized.equals("male") || normalized.equals("m") || normalized.equals("man"))
      return "Male";
    if (normalized.equals("female") || normalized.equals("f") || normalized.equals("woman"))
      return "Female";

    return gender;
  }

  /** Respect rate limiting for external APIs */
  private boolean respectRateLimit(String source) {
    long currentTime = System.currentTimeMillis();
    Long lastRequest = lastRequestTime.get(source);

    if (lastRequest != null && (currentTime - lastRequest) < RATE_LIMIT_MS) {
      return false; // Too soon since last request
    }

    lastRequestTime.put(source, currentTime);
    return true;
  }

  /** Get cache size for monitoring */
  public int getCacheSize() {
    return cache.size();
  }

  /** Clear the cache */
  public void clearCache() {
    cache.clear();
    log.info("External NPC data cache cleared");
  }

  /** Check if OSRSBox static data has been loaded */
  public boolean isOsrsBoxDataLoaded() {
    return osrsBoxDataLoaded;
  }

  /** Get the number of OSRSBox entries loaded */
  public int getOsrsBoxDataSize() {
    return osrsBoxData.size();
  }
}
